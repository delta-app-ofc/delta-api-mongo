package br.com.delta.delta_api_mongo.modules.detection.model;

import java.util.List;
import java.util.Map;

/**
 * Resultado de uma janela. anomalyDetected null significa "não avaliada" (nunca "sem vazamento");
 * nesse caso error explica o motivo.
 */
public record DetectionResult(Boolean anomalyDetected, List<String> reasons, Map<String, Object> evidence,
                              String error) {

    public static DetectionResult notEvaluated(String error, Map<String, Object> evidence) {
        return new DetectionResult(null, List.of(), evidence, error);
    }
}
