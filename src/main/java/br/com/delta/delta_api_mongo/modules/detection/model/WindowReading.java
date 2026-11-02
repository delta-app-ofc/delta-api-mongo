package br.com.delta.delta_api_mongo.modules.detection.model;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import java.time.Duration;
import java.time.Instant;

/** Uma janela de consumo já normalizada para o detector (litros e vazão média em L/min). */
public record WindowReading(String deviceId, Instant start, Instant end, double liters, double lpm) {

    public static WindowReading from(ConsumptionSummaryDocument document) {
        double minutes = Math.max(Duration.between(document.getWindowStartedAt(), document.getWindowFinishedAt())
                .toSeconds() / 60.0, 1.0 / 60);
        double liters = document.getConsumptionLiters();
        double lpm = document.getLpmAverage() != null ? document.getLpmAverage() : liters / minutes;
        return new WindowReading(document.getDeviceId(), document.getWindowStartedAt(),
                document.getWindowFinishedAt(), liters, lpm);
    }

    public long seconds() {
        return Duration.between(start, end).toSeconds();
    }
}
