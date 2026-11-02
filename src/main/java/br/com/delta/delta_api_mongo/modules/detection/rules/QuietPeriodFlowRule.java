package br.com.delta.delta_api_mongo.modules.detection.rules;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.BusinessSchedule;
import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Água correndo num período em que não deveria haver uso:
 * - residência: madrugada (Minimum Night Flow), regra "night_floor";
 * - empresa: fora do expediente cadastrado (turnos e dias de funcionamento), regra "off_hours_flow".
 * Exige várias janelas seguidas acima do piso aprendido do imóvel, para ignorar um uso isolado (ex.: alguém
 * que ficou até mais tarde ou foi ao banheiro de madrugada).
 */
public class QuietPeriodFlowRule {
    public static final String NIGHT_FLOOR = "night_floor";
    public static final String OFF_HOURS_FLOW = "off_hours_flow";

    private final DetectionProperties properties;

    public QuietPeriodFlowRule(DetectionProperties properties) {
        this.properties = properties;
    }

    /** Madrugada no fuso configurado (00h às 05h por padrão). */
    public boolean isNight(WindowReading window) {
        int hour = window.start().atZone(properties.zone()).getHour();
        return hour >= properties.night().startHour() && hour < properties.night().endHour();
    }

    /** Fora do expediente da empresa; false quando o horário de funcionamento não está cadastrado. */
    public Predicate<WindowReading> outsideBusinessHours(BusinessSchedule schedule) {
        return window -> schedule != null && schedule.isDefined()
                && !schedule.isOpen(window.start(), properties.zone());
    }

    public Optional<Map<String, Object>> evaluateNight(List<WindowReading> recentNewestFirst, Double floorLpm) {
        return evaluate(recentNewestFirst, floorLpm, this::isNight, NIGHT_FLOOR);
    }

    public Optional<Map<String, Object>> evaluateOffHours(List<WindowReading> recentNewestFirst, Double floorLpm,
                                                          BusinessSchedule schedule) {
        return evaluate(recentNewestFirst, floorLpm, outsideBusinessHours(schedule), OFF_HOURS_FLOW);
    }

    private Optional<Map<String, Object>> evaluate(List<WindowReading> recentNewestFirst, Double floorLpm,
                                                   Predicate<WindowReading> quiet, String ruleName) {
        int needed = properties.night().minWindows();
        if (recentNewestFirst.size() < needed) return Optional.empty();
        var run = recentNewestFirst.subList(0, needed);
        if (!Contiguous.isContiguous(run, properties.window().toleranceSeconds())) return Optional.empty();

        double floor = floorLpm == null ? 0 : floorLpm;
        double threshold = Math.max(properties.night().minLpm(), floor * properties.night().floorFactor());
        boolean allAbove = run.stream().allMatch(window -> quiet.test(window) && window.liters() > 0
                && window.lpm() > threshold);
        if (!allAbove) return Optional.empty();

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("rule", ruleName);
        evidence.put("windows", needed);
        evidence.put("threshold_lpm", Math.round(threshold * 1000.0) / 1000.0);
        evidence.put("floor_lpm", Math.round(floor * 1000.0) / 1000.0);
        evidence.put("lpm_min", Math.round(run.stream().mapToDouble(WindowReading::lpm).min().orElse(0) * 1000.0) / 1000.0);
        evidence.put("first_window_started_at", run.get(needed - 1).start().toString());
        evidence.put("last_window_finished_at", run.get(0).end().toString());
        return Optional.of(evidence);
    }
}
