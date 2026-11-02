package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import br.com.delta.delta_api_mongo.modules.detection.rules.ContinuousFlowRule;
import br.com.delta.delta_api_mongo.modules.detection.rules.QuietPeriodFlowRule;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Único escritor dos alertas do detector em alerts_history. Regras:
 * - um alerta aberto por dispositivo, regra e usuário (dedup_key); enquanto está aberto, não cria outro;
 * - um documento por usuário do imóvel;
 * - o alerta fecha depois de resolveWindows janelas seguidas sem a regra disparar;
 * - sem dono conhecido não há para quem alertar: a janela continua marcada, mas nenhum alerta é criado.
 * O tipo do alerta depende da regra; consumo fora do expediente de uma empresa e mudança de padrão têm tipo próprio.
 */
@Service
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class AlertService {
    public static final String ROBUST_ZSCORE = "robust_zscore";
    public static final String REGIME_SHIFT = "regime_shift";
    public static final String SOURCE = "detector";
    static final List<String> RULES = List.of(ContinuousFlowRule.NAME, QuietPeriodFlowRule.NIGHT_FLOOR,
            QuietPeriodFlowRule.OFF_HOURS_FLOW, ROBUST_ZSCORE, REGIME_SHIFT);
    private static final String CLEAR_PREFIX = "clear:";

    private final AlertHistoryRepository repository;
    private final DetectionProperties properties;

    public AlertService(AlertHistoryRepository repository, DetectionProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    public static String alertType(String rule) {
        return switch (rule) {
            case ContinuousFlowRule.NAME, QuietPeriodFlowRule.NIGHT_FLOOR -> "vazamento_continuo";
            case QuietPeriodFlowRule.OFF_HOURS_FLOW -> "consumo_fora_do_expediente";
            case REGIME_SHIFT -> "mudanca_padrao";
            default -> "fluxo_atipico";
        };
    }

    static String severity(String rule) {
        return switch (rule) {
            case ROBUST_ZSCORE -> "medium";
            case REGIME_SHIFT -> "low";
            default -> "high";
        };
    }

    static String dedupKey(String deviceId, String rule, Integer userId) {
        return SOURCE + ":" + deviceId + ":" + rule + ":" + userId;
    }

    /** Abre alertas para as regras que dispararam e encerra os das regras que ficaram normais. */
    public void sync(String deviceId, Optional<DeviceOwner> owner, List<String> firedRules,
                     Map<String, Object> evidence, Instant windowEnd, UserHourBaselineDocument state) {
        for (String rule : RULES) {
            var open = repository.findByDeviceIdAndRuleAndResolvedAtIsNull(deviceId, rule);
            if (firedRules.contains(rule)) {
                state.setCounter(CLEAR_PREFIX + rule, 0);
                if (open.isEmpty()) {
                    if (owner.isPresent()) openFor(deviceId, owner.get(), rule, evidence, windowEnd);
                } else {
                    open.forEach(alert -> refresh(alert, rule, windowEnd));
                }
            } else if (!open.isEmpty()) {
                int cleared = state.counter(CLEAR_PREFIX + rule) + 1;
                if (cleared >= properties.alert().resolveWindows()) {
                    open.forEach(alert -> alert.setResolvedAt(windowEnd));
                    repository.saveAll(open);
                    cleared = 0;
                }
                state.setCounter(CLEAR_PREFIX + rule, cleared);
            }
        }
    }

    private void openFor(String deviceId, DeviceOwner owner, String rule, Map<String, Object> windowEvidence,
                         Instant windowEnd) {
        var evidence = AlertContent.evidence(rule, windowEvidence, properties.window().minutes());
        var alerts = owner.userIds().stream().map(userId -> {
            var alert = new AlertHistoryDocument();
            alert.setDeviceId(deviceId);
            alert.setPropertyId(owner.propertyId());
            alert.setUserId(userId);
            alert.setAlertType(alertType(rule));
            alert.setTriggeredAt(windowEnd);
            alert.setSeverity(severity(rule));
            alert.setSource(SOURCE);
            alert.setDedupKey(dedupKey(deviceId, rule, userId));
            alert.setRule(rule);
            alert.setTitle(AlertContent.title(rule));
            alert.setMessage(AlertContent.message(rule, evidence));
            alert.setEvidence(evidence);
            return alert;
        }).toList();
        repository.saveAll(alerts);
    }

    /** Alerta de fluxo ainda aberto: atualiza há quanto tempo o fluxo dura, para a tela mostrar o tempo real. */
    private void refresh(AlertHistoryDocument alert, String rule, Instant windowEnd) {
        if (!AlertContent.isFlowRule(rule) || alert.getEvidence() == null
                || !(alert.getEvidence().get("initial_duration_minutes") instanceof Number initial)) {
            return;
        }
        long elapsed = Duration.between(alert.getTriggeredAt(), windowEnd).toMinutes();
        var evidence = new java.util.LinkedHashMap<>(alert.getEvidence());
        evidence.put("duration_minutes", initial.longValue() + Math.max(elapsed, 0));
        alert.setEvidence(evidence);
        alert.setMessage(AlertContent.message(rule, evidence));
        repository.save(alert);
    }
}
