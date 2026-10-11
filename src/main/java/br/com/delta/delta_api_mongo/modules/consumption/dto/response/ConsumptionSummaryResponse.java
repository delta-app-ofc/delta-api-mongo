package br.com.delta.delta_api_mongo.modules.consumption.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record ConsumptionSummaryResponse(
        @JsonProperty("_id") String id,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("window_started_at") Instant windowStartedAt,
        @JsonProperty("window_finished_at") Instant windowFinishedAt,
        @JsonProperty("consumption_liters") Double consumptionLiters,
        @JsonProperty("lpm_average") Double lpmAverage
) {
}
