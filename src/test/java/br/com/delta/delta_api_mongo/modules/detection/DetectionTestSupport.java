package br.com.delta.delta_api_mongo.modules.detection;

import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.model.WindowReading;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Dados e configuração padrão compartilhados pelos testes do detector. */
public final class DetectionTestSupport {
    /** Segunda-feira, 00:00 em São Paulo (03:00Z). */
    public static final Instant MONDAY_MIDNIGHT = Instant.parse("2026-07-13T03:00:00Z");
    public static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    private DetectionTestSupport() {
    }

    /** Configuração com todos os valores padrão, só com o detector ligado. */
    public static DetectionProperties properties() {
        return new DetectionProperties(true, "test", ZONE, new DetectionProperties.Window(5, 30, 15),
                new DetectionProperties.Baseline(28, 4, 0.4, 0.1), new DetectionProperties.ZScore(4.0, 5.0, 4),
                new DetectionProperties.ContinuousFlow(6, 0.35), new DetectionProperties.Night(0, 5, 3, 0.05, 3.0),
                new DetectionProperties.PageHinkley(0.5, 10.0, 4.0), new DetectionProperties.Regime(24, 30, 12, 1.0),
                new DetectionProperties.Alert(36), new DetectionProperties.Cohort(true, 5, 7, 10),
                new DetectionProperties.Owner("", "", 2000, 30));
    }

    public static WindowReading window(Instant start, double liters, double lpm) {
        return new WindowReading("DEV-1", start, start.plusSeconds(300), liters, lpm);
    }

    /** count janelas de 5 minutos seguidas a partir de start, da mais antiga para a mais nova. */
    public static List<WindowReading> run(Instant start, int count, double liters, double lpm) {
        var windows = new ArrayList<WindowReading>();
        for (int i = 0; i < count; i++) windows.add(window(start.plusSeconds(300L * i), liters, lpm));
        return windows;
    }

    public static List<WindowReading> newestFirst(List<WindowReading> oldestFirst) {
        var copy = new ArrayList<>(oldestFirst);
        java.util.Collections.reverse(copy);
        return copy;
    }
}
