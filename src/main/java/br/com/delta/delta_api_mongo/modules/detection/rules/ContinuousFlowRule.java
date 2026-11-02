package br.com.delta.delta_api_mongo.modules.detection.rules;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fluxo contínuo: vários períodos seguidos de 5 minutos com água passando e vazão quase constante.
 * Uso normal (chuveiro, torneira) para e volta; vazamento não para e mantém a mesma vazão.
 */
public class ContinuousFlowRule {
    public static final String NAME = "continuous_flow";

    private final DetectionProperties properties;

    public ContinuousFlowRule(DetectionProperties properties) {
        this.properties = properties;
    }

    /** recentNewestFirst: janelas do dispositivo da mais nova (a atual) para a mais antiga. */
    public Optional<Map<String, Object>> evaluate(List<WindowReading> recentNewestFirst) {
        int needed = properties.continuousFlow().minWindows();
        if (recentNewestFirst.size() < needed) return Optional.empty();
        var run = recentNewestFirst.subList(0, needed);
        if (!Contiguous.isContiguous(run, properties.window().toleranceSeconds())) return Optional.empty();
        if (run.stream().anyMatch(window -> window.liters() <= 0.2)) return Optional.empty();

        double mean = run.stream().mapToDouble(WindowReading::lpm).average().orElse(0);
        double variance = run.stream().mapToDouble(window -> Math.pow(window.lpm() - mean, 2)).average().orElse(0);
        double cv = mean > 0 ? Math.sqrt(variance) / mean : Double.POSITIVE_INFINITY;
        if (cv > properties.continuousFlow().maxCv()) return Optional.empty();

        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("rule", NAME);
        evidence.put("windows", needed);
        evidence.put("lpm_mean", round(mean));
        evidence.put("lpm_cv", round(cv));
        evidence.put("max_cv", properties.continuousFlow().maxCv());
        evidence.put("first_window_started_at", run.get(needed - 1).start().toString());
        evidence.put("last_window_finished_at", run.get(0).end().toString());
        return Optional.of(evidence);
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
