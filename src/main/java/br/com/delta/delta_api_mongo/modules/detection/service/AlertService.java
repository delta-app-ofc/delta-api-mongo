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
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Único escritor de alerts_history. Regras:
 * - um alerta aberto por dispositivo e regra (enquanto está aberto, não cria outro);
 * - um documento por usuário do imóvel;
 * - o alerta fecha depois de resolveWindows janelas seguidas sem a regra disparar;
 * - sem dono conhecido não há para quem alertar: a janela continua marcada, mas nenhum alerta é criado.
 * O tipo do alerta depende da regra; consumo fora do expediente de uma empresa tem tipo próprio.
 */
@Service
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class AlertService {
    public static final String ROBUST_ZSCORE = "robust_zscore";
    static final List<String> RULES = List.of(ContinuousFlowRule.NAME, QuietPeriodFlowRule.NIGHT_FLOOR,
            QuietPeriodFlowRule.OFF_HOURS_FLOW, ROBUST_ZSCORE);
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
            default -> "fluxo_atipico";
        };
    }

    static String severity(String rule) {
        return ROBUST_ZSCORE.equals(rule) ? "medium" : "high";
    }

    /** Abre alertas para as regras que dispararam e encerra os das regras que ficaram normais. */
    public void sync(String deviceId, Optional<DeviceOwner> owner, List<String> firedRules,
                     Map<String, Object> evidence, Instant windowEnd, UserHourBaselineDocument state) {
        for (String rule : RULES) {
            var open = repository.findByDeviceIdAndRuleAndResolvedAtIsNull(deviceId, rule);
            if (firedRules.contains(rule)) {
                state.setCounter(CLEAR_PREFIX + rule, 0);
                if (open.isEmpty() && owner.isPresent()) openFor(deviceId, owner.get(), rule, evidence, windowEnd);
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

    private void openFor(String deviceId, DeviceOwner owner, String rule, Map<String, Object> evidence,
                         Instant windowEnd) {
        var alerts = owner.userIds().stream().map(userId -> {
            var alert = new AlertHistoryDocument();
            alert.setDeviceId(deviceId);
            alert.setUserId(userId);
            alert.setAlertType(alertType(rule));
            alert.setTriggeredAt(windowEnd);
            alert.setSeverity(severity(rule));
            alert.setRule(rule);
            alert.setEvidence(evidence);
            return alert;
        }).toList();
        repository.saveAll(alerts);
    }
}
