package br.com.delta.delta_api_mongo.modules.consumption.dto.request;

import br.com.delta.delta_api_mongo.common.devicevalidation.DeviceIdDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;

public record ConsumptionSummaryRequest(
        @NotBlank @Size(max = 100)
        @JsonDeserialize(using = DeviceIdDeserializer.class)
        @JsonProperty("device_id") String deviceId,
        @NotNull @JsonProperty("window_started_at") Instant windowStartedAt,
        @NotNull @JsonProperty("window_finished_at") Instant windowFinishedAt,
        @NotNull @PositiveOrZero @JsonProperty("consumption_liters") Double consumptionLiters,
        @PositiveOrZero @JsonProperty("lpm_average") Double lpmAverage
) {
}
