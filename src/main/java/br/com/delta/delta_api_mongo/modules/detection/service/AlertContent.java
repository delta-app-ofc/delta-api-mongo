package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.rules.ContinuousFlowRule;
import br.com.delta.delta_api_mongo.modules.detection.rules.QuietPeriodFlowRule;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Conteúdo de um alerta: evidência padronizada e texto (título e mensagem) em português, por template.
 * A evidência usa as mesmas chaves para as regras de fluxo, para o app e a IA somarem litros/dia e pontos:
 * lpm, duration_minutes, initial_duration_minutes e liters_per_day_estimate.
 */
final class AlertContent {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private AlertContent() {
    }

    static boolean isFlowRule(String rule) {
        return ContinuousFlowRule.NAME.equals(rule) || QuietPeriodFlowRule.NIGHT_FLOOR.equals(rule)
                || QuietPeriodFlowRule.OFF_HOURS_FLOW.equals(rule);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> evidence(String rule, Map<String, Object> window, int windowMinutes) {
        var out = new LinkedHashMap<String, Object>();
        Object rules = window.get("rules");
        if (rules instanceof Map<?, ?> byRule && byRule.get(rule) instanceof Map<?, ?> found) {
            out.putAll((Map<String, Object>) found);
        }
        out.put("rule", rule);
        if (isFlowRule(rule)) {
            Object lpmValue = ContinuousFlowRule.NAME.equals(rule) ? out.get("lpm_mean") : out.get("lpm_min");
            double lpm = number(lpmValue);
            int windows = (int) number(out.get("windows"));
            out.put("lpm", round(lpm, 3));
            out.put("duration_minutes", windows * windowMinutes);
            out.put("initial_duration_minutes", windows * windowMinutes);
            out.put("liters_per_day_estimate", Math.round(lpm * 60 * 24));
            out.put("period_start", out.get("first_window_started_at"));
            out.put("period_end", out.get("last_window_finished_at"));
        } else if (AlertService.ROBUST_ZSCORE.equals(rule)) {
            out.put("value", window.get("liters"));
            out.put("baseline", window.get("baseline_median"));
            out.put("lpm", window.get("lpm"));
        }
        return out;
    }

    static String title(String rule) {
        return switch (rule) {
            case ContinuousFlowRule.NAME, QuietPeriodFlowRule.NIGHT_FLOOR -> "Possível vazamento";
            case QuietPeriodFlowRule.OFF_HOURS_FLOW -> "Consumo fora do expediente";
            case AlertService.ROBUST_ZSCORE -> "Pico atípico de consumo";
            case AlertService.REGIME_SHIFT -> "Mudança de padrão de consumo";
            default -> "Alerta de consumo";
        };
    }

    static String message(String rule, Map<String, Object> evidence) {
        return switch (rule) {
            case ContinuousFlowRule.NAME -> String.format(PT_BR, "Fluxo contínuo de %.1f L/min há %d min sem pausa",
                    number(evidence.get("lpm")), (long) number(evidence.get("duration_minutes")));
            case QuietPeriodFlowRule.NIGHT_FLOOR -> String.format(PT_BR,
                    "Fluxo de %.1f L/min na madrugada há %d min, sem uso previsto",
                    number(evidence.get("lpm")), (long) number(evidence.get("duration_minutes")));
            case QuietPeriodFlowRule.OFF_HOURS_FLOW -> String.format(PT_BR,
                    "Água correndo fora do horário de funcionamento há %d min (%.1f L/min)",
                    (long) number(evidence.get("duration_minutes")), number(evidence.get("lpm")));
            case AlertService.ROBUST_ZSCORE -> String.format(PT_BR,
                    "%.1f L em 5 min, acima dos %.1f L esperados para este horário",
                    number(evidence.get("value")), number(evidence.get("baseline")));
            case AlertService.REGIME_SHIFT ->
                    "O consumo subiu de patamar e se mantém assim; pode ser visita ou mudança de rotina.";
            default -> "Consumo fora do padrão.";
        };
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    private static double round(double value, int places) {
        double factor = Math.pow(10, places);
        return Math.round(value * factor) / factor;
    }
}
