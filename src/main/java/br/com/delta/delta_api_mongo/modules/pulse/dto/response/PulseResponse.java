package br.com.delta.delta_api_mongo.modules.pulse.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

public record PulseResponse(
        @JsonProperty("_id")
        String id,

        @JsonProperty("device_id")
        String deviceId,

        @JsonProperty("sent_at")
        Instant sentAt,

        @JsonProperty("window_minutes")
        Integer windowMinutes,

        @JsonProperty("total_pulses")
        Integer totalPulses,

        List<PulseItemResponse> pulses
) {

    public record PulseItemResponse(
            @JsonProperty("pulsed_at")
            Instant pulsedAt,

            @JsonProperty("ms_since_boot")
            Long msSinceBoot,

            @JsonProperty("delta_ms")
            Long deltaMs
    ) {
    }
}
