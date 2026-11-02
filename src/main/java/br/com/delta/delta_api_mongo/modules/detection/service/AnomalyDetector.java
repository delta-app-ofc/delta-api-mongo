package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import br.com.delta.delta_api_mongo.modules.detection.rules.ContinuousFlowRule;
import br.com.delta.delta_api_mongo.modules.detection.rules.QuietPeriodFlowRule;
import br.com.delta.delta_api_mongo.modules.detection.rules.RobustStats;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Avalia uma janela de consumo recém-gravada. Passos:
 * 1. qualidade dos dados: janela fora do padrão ou mais antiga que a última processada não é avaliada;
 * 2. regras: fluxo contínuo, consumo noturno (residência) ou fora do expediente (empresa) e z-score robusto;
 * 3. regime: Page-Hinkley acompanha degraus de consumo (visita, novo normal);
 * 4. baseline: janela normal alimenta o baseline; janela suspeita fica de fora para não "ensinar" o vazamento;
 * 5. alertas: abre/fecha alertas em alerts_history.
 * Quem chama é responsável por tratar exceções (ver AnomalyDetectionService).
 */
@Service
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class AnomalyDetector {
    static final String Z_STREAK = "z_streak";
    static final String REGIME_ALERT = "regime_alert";
    static final String LEAK_RECENT = "leak_recent";
    private static final int LEAK_MEMORY_WINDOWS = 288;
    private static final int MIN_RECENT_WINDOWS = 12;

    private final DetectionProperties properties;
    private final ConsumptionSummaryRepository consumptions;
    private final BaselineService baselines;
    private final CohortBaseline cohort;
    private final DeviceOwnerResolver owners;
    private final AlertService alerts;
    private final ContinuousFlowRule continuousFlow;
    private final QuietPeriodFlowRule quietPeriod;
    private final RegimeTracker regimeTracker;

    public AnomalyDetector(DetectionProperties properties, ConsumptionSummaryRepository consumptions,
                           BaselineService baselines, CohortBaseline cohort, DeviceOwnerResolver owners,
                           AlertService alerts) {
        this.properties = properties;
        this.consumptions = consumptions;
        this.baselines = baselines;
        this.cohort = cohort;
        this.owners = owners;
        this.alerts = alerts;
        this.continuousFlow = new ContinuousFlowRule(properties);
        this.quietPeriod = new QuietPeriodFlowRule(properties);
        this.regimeTracker = new RegimeTracker(properties);
    }

    /** O mesmo monitor da avaliação protege a declaração de visita contra a gravação de um estado antigo. */
    public synchronized void declareVisit(String deviceId, Instant until, Integer userId, Instant now) {
        var state = baselines.deviceState(deviceId);
        state.setVisitUntil(until);
        state.setVisitUpdatedBy(userId);
        state.setVisitUpdatedAt(now);
        baselines.save(state);
    }

    public synchronized DetectionResult evaluate(ConsumptionSummaryDocument document) {
        WindowReading window = WindowReading.from(document);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("config_version", properties.configVersion());
        evidence.put("liters", window.liters());
        evidence.put("lpm", window.lpm());

        long expected = properties.window().minutes() * 60L;
        if (Math.abs(window.seconds() - expected) > properties.window().toleranceSeconds()) {
            evidence.put("window_seconds", window.seconds());
            return DetectionResult.notEvaluated("Janela fora do padrão de " + properties.window().minutes()
                    + " minutos; não avaliada.", evidence);
        }

        UserHourBaselineDocument state = baselines.deviceState(window.deviceId());
        if (state.getLastWindowFinishedAt() != null && !window.end().isAfter(state.getLastWindowFinishedAt())) {
            evidence.put("last_processed_window_finished_at", state.getLastWindowFinishedAt().toString());
            return DetectionResult.notEvaluated("Janela anterior ou igual à última processada; baseline preservado.",
                    evidence);
        }
        boolean dataGap = state.getLastWindowFinishedAt() != null && Duration
                .between(state.getLastWindowFinishedAt(), window.start()).toMinutes() > properties.window().maxGapMinutes();
        if (dataGap) {
            // Janelas perdidas / dispositivo offline: zera as somas e as confirmações, sem tratar como anomalia.
            evidence.put("data_gap", true);
            state.setCusumPos(0.0);
            state.setCusumNeg(0.0);
            state.setCounter(Z_STREAK, 0);
        }

        Optional<DeviceOwner> owner = owners.resolve(window.deviceId());
        int hour = BaselineService.hourOfWeek(window.start(), properties.zone());
        UserHourBaselineDocument slot = baselines.slot(window.deviceId(), hour);
        owner.ifPresent(found -> slot.setCohortKey(found.cohortKey()));
        List<WindowReading> recent = recentWindows(window);

        evidence.put("hour_of_week", hour);
        String regime = state.getRegimeState() == null ? "NORMAL" : state.getRegimeState();
        evidence.put("regime_state", regime);
        owner.ifPresent(found -> evidence.put("classification", found.classificationGroup()));

        // --- z-score robusto: contra o baseline lento, ou o rápido enquanto o regime está deslocado ---
        Double zSlow = zAgainstSlow(window, slot, hour, evidence);
        Double zUsed = "SHIFTED".equals(regime) ? zAgainstFast(window, slot, evidence) : zSlow;
        boolean zCandidate = zUsed != null && zUsed > properties.zscore().threshold();
        int streak = zCandidate ? state.counter(Z_STREAK) + 1 : 0;
        state.setCounter(Z_STREAK, streak);
        boolean zConfirmed = streak >= properties.zscore().confirmWindows();

        // --- regras de fluxo ---
        List<String> reasons = new ArrayList<>();
        Map<String, Object> ruleEvidence = new LinkedHashMap<>();
        continuousFlow.evaluate(recent).ifPresent(found -> {
            reasons.add(ContinuousFlowRule.NAME);
            ruleEvidence.put(ContinuousFlowRule.NAME, found);
        });
        boolean quietWindow = quietPeriod.isNight(window);
        if (owner.map(DeviceOwner::isResidential).orElse(false)) {
            quietPeriod.evaluateNight(recent, slot.getNightFloorLpm()).ifPresent(found -> {
                reasons.add(QuietPeriodFlowRule.NIGHT_FLOOR);
                ruleEvidence.put(QuietPeriodFlowRule.NIGHT_FLOOR, found);
            });
        } else if (owner.map(DeviceOwner::isCommercial).orElse(false)) {
            var schedule = owner.get().schedule();
            quietWindow = quietPeriod.outsideBusinessHours(schedule).test(window);
            quietPeriod.evaluateOffHours(recent, slot.getNightFloorLpm(), schedule).ifPresent(found -> {
                reasons.add(QuietPeriodFlowRule.OFF_HOURS_FLOW);
                ruleEvidence.put(QuietPeriodFlowRule.OFF_HOURS_FLOW, found);
            });
        }
        if (zConfirmed) {
            reasons.add(AlertService.ROBUST_ZSCORE);
            ruleEvidence.put(AlertService.ROBUST_ZSCORE, Map.of("z", zUsed, "threshold",
                    properties.zscore().threshold(), "consecutive_windows", streak));
        }

        // Lembra por 24 h (288 janelas) que houve fluxo suspeito: uma subida de consumo nesse período é vazamento
        // provável, não mudança de rotina.
        boolean flowHit = reasons.stream().anyMatch(reason -> !AlertService.ROBUST_ZSCORE.equals(reason));
        state.setCounter(LEAK_RECENT, flowHit ? LEAK_MEMORY_WINDOWS : Math.max(0, state.counter(LEAK_RECENT) - 1));

        // --- regime: o Page-Hinkley recebe um resíduo por hora (ver hourlyResidual) ---
        Double residual = hourlyResidual(state, slot, window, dataGap);
        var outcome = regimeTracker.update(state, residual, window.end(), dataGap);
        if (outcome.enteredShifted()) {
            boolean visitDeclared = state.getVisitUntil() != null && state.getVisitUntil().isAfter(window.end());
            String change = visitDeclared ? "DECLARED_VISIT"
                    : (state.counter(LEAK_RECENT) > 0 ? "LEAK_SUSPECT" : "ROUTINE_CHANGE");
            evidence.put("regime_change", change);
            evidence.put("regime_since", window.end().toString());
            // Só uma subida de consumo sem outra explicação gera o alerta informativo de mudança de padrão.
            state.setCounter(REGIME_ALERT, "ROUTINE_CHANGE".equals(change)
                    && state.counter(RegimeTracker.DIRECTION) > 0 ? 1 : 0);
        }
        if (outcome.returned()) evidence.put("regime_change", "RETURNED_TO_NORMAL");
        if (outcome.promoted()) {
            baselines.promoteFastToSlow(window.deviceId());
            baselines.promoteSlot(slot);
            evidence.put("regime_change", "PROMOTED_TO_BASELINE");
        }
        evidence.put("regime_state_after", state.getRegimeState());
        if (!"SHIFTED".equals(state.getRegimeState())) state.setCounter(REGIME_ALERT, 0);
        boolean regimeAlert = state.counter(REGIME_ALERT) == 1;
        if (regimeAlert) {
            ruleEvidence.put(AlertService.REGIME_SHIFT, Map.of("regime_change", "ROUTINE_CHANGE",
                    "regime_since", String.valueOf(state.getRegimeSince())));
        }

        // --- baseline: só janela normal ensina o "normal" ---
        boolean suspicious = !reasons.isEmpty() || zCandidate;
        if (!suspicious) {
            baselines.updateFast(slot, window.liters());
            baselines.updateFloors(slot, window.lpm(), quietWindow);
            String current = state.getRegimeState();
            if ("NORMAL".equals(current) || "RETURNING".equals(current)) baselines.acceptSlow(slot, window.liters());
        }
        if (!ruleEvidence.isEmpty()) evidence.put("rules", ruleEvidence);

        // --- persistência e alertas ---
        state.setLastWindowFinishedAt(window.end());
        state.setConfigVersion(properties.configVersion());
        state.setUpdatedAt(window.end());
        slot.setConfigVersion(properties.configVersion());
        slot.setUpdatedAt(window.end());
        slot.setLastWindowFinishedAt(window.end());
        owner.ifPresent(found -> {
            slot.setPropertyId(found.propertyId());
            state.setPropertyId(found.propertyId());
        });
        // O alerta de mudança de padrão não torna a janela anômala: é informativo, e a janela segue "normal".
        var alertRules = new ArrayList<>(reasons);
        if (regimeAlert) alertRules.add(AlertService.REGIME_SHIFT);
        alerts.sync(window.deviceId(), owner, alertRules, evidence, window.end(), state);
        baselines.save(slot);
        baselines.save(state);

        return new DetectionResult(!reasons.isEmpty(), List.copyOf(reasons), evidence, null);
    }

    /**
     * Soma, hora a hora, quanto o consumo real passou do esperado pela média do slot (padronizado pelo desvio).
     * Uma janela sozinha tem pouco sinal (uso é em rajadas); uma hora inteira, incluindo as janelas sem consumo,
     * tem média zero numa casa estável e fica claramente positiva numa visita.
     * Devolve o resíduo da hora que acabou de fechar, ou null se ainda não fechou / não há dados suficientes.
     */
    private Double hourlyResidual(UserHourBaselineDocument state, UserHourBaselineDocument slot,
                                  WindowReading window, boolean dataGap) {
        var acc = state.getRegimeAcc();
        double hourKey = window.start().atZone(properties.zone()).toLocalDateTime()
                .truncatedTo(java.time.temporal.ChronoUnit.HOURS).toEpochSecond(java.time.ZoneOffset.UTC) / 3600.0;
        Double residual = null;
        if (dataGap) {
            acc.clear();
        } else if (!acc.isEmpty() && acc.get("hour_key") != hourKey) {
            double expectedVar = acc.getOrDefault("var", 0.0);
            // Hora com janelas faltando (menos de 2/3) não vale: seria comparar uma hora parcial.
            double minWindows = Math.ceil(3600.0 / (properties.window().minutes() * 60) * 2 / 3);
            if (acc.getOrDefault("windows", 0.0) >= minWindows && expectedVar > 0) {
                residual = (acc.get("liters") - acc.get("expected")) / Math.sqrt(expectedVar);
            }
            acc.clear();
        }
        if (baselines.hasSlowHistory(slot)) {
            double sampleMean = slot.getSamples().stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double mean = sampleMean;
            double variance = slot.getSamples().stream().mapToDouble(v -> Math.pow(v - sampleMean, 2)).average().orElse(0);
            // A promoção consolida média e dispersão; uma amostra-resumo não representa variância zero.
            if (slot.isSlowReady()) {
                mean = slot.getMedian();
                variance = Math.pow((slot.getMad() == null ? 0 : slot.getMad()) * RobustStats.MAD_TO_SIGMA, 2);
            }
            double sigma = Math.max(Math.sqrt(variance), properties.regime().minSigmaLiters());
            acc.merge("liters", window.liters(), Double::sum);
            acc.merge("expected", mean, Double::sum);
            acc.merge("var", sigma * sigma, Double::sum);
            acc.merge("windows", 1.0, Double::sum);
        }
        acc.put("hour_key", hourKey);
        return residual;
    }

    private List<WindowReading> recentWindows(WindowReading current) {
        int needed = Math.max(MIN_RECENT_WINDOWS, Math.max(properties.continuousFlow().minWindows(),
                properties.night().minWindows()));
        List<WindowReading> found = consumptions.findRecentByDevice(current.deviceId(), current.start(),
                PageRequest.of(0, needed)).stream().map(WindowReading::from).toList();
        // Garante que a janela atual é a primeira, mesmo se a leitura ainda não a trouxer.
        if (found.isEmpty() || !found.get(0).start().equals(current.start())) {
            var withCurrent = new ArrayList<WindowReading>();
            withCurrent.add(current);
            found.stream().filter(w -> w.start().isBefore(current.start())).forEach(withCurrent::add);
            return withCurrent;
        }
        return found;
    }

    private Double zAgainstSlow(WindowReading window, UserHourBaselineDocument slot, int hour, Map<String, Object> evidence) {
        double median;
        double mad;
        if (baselines.hasSlowHistory(slot)) {
            median = slot.getMedian();
            mad = slot.getMad();
            evidence.put("baseline_source", "device");
            evidence.put("baseline_samples", slot.getSamples().size());
        } else {
            var reference = cohort.reference(hour, slot.getCohortKey(), window.deviceId());
            if (reference.isEmpty()) {
                evidence.put("baseline_source", "none");
                return null;
            }
            median = reference.get().median();
            mad = reference.get().mad();
            evidence.put("baseline_source", "cohort");
            evidence.put("cohort_devices", reference.get().devices());
        }
        double z = RobustStats.robustZ(window.liters(), median, mad, properties.zscore().minScaleLiters());
        evidence.put("baseline_median", median);
        evidence.put("baseline_mad", mad);
        evidence.put("z", Math.round(z * 1000.0) / 1000.0);
        return z;
    }

    private Double zAgainstFast(WindowReading window, UserHourBaselineDocument slot, Map<String, Object> evidence) {
        if (slot.getEwmaMean() == null || slot.getEwmaVar() == null) return null;
        double scale = Math.max(Math.sqrt(slot.getEwmaVar()), properties.zscore().minScaleLiters());
        double z = (window.liters() - slot.getEwmaMean()) / scale;
        evidence.put("baseline_source", "fast");
        evidence.put("z_fast", Math.round(z * 1000.0) / 1000.0);
        return z;
    }
}
