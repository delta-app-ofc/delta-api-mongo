package br.com.delta.delta_api_mongo.modules.alerts.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/** Cartão "Vazamentos identificados": só litros/dia e pontos; o custo em R$ é calculado pela IA/app com a tarifa. */
public record LeakSummaryResponse(
        @JsonProperty("liters_per_day") long litersPerDay,
        int points,
        @JsonProperty("window_hours") int windowHours,
        Instant since
) {
}
