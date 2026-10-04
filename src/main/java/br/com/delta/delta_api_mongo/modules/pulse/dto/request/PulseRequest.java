package br.com.delta.delta_api_mongo.modules.pulse.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.util.List;

public record PulseRequest(
        @NotBlank
        @JsonProperty("device_id")
        String deviceId,

        @NotNull
        @JsonProperty("sent_at")
        Instant sentAt,

        @NotNull
        @Positive
        @JsonProperty("window_minutes")
        Integer windowMinutes,

        @NotNull
        @PositiveOrZero
        @JsonProperty("total_pulses")
        Integer totalPulses,

        @NotNull
        List<@NotNull @Valid PulseItemRequest> pulses
) {

    public record PulseItemRequest(
            @NotNull
            @JsonProperty("pulsed_at")
            Instant pulsedAt,

            @NotNull
            @PositiveOrZero
            @JsonProperty("ms_since_boot")
            Long msSinceBoot,

            @NotNull
            @PositiveOrZero
            @JsonProperty("delta_ms")
            Long deltaMs
    ) {
    }
}
