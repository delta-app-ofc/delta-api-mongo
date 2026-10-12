package br.com.delta.delta_api_mongo.modules.device_status.dto.request;

import br.com.delta.delta_api_mongo.common.devicevalidation.DeviceIdDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;
import java.time.Instant;

public record DeviceStatusRequest(
        @NotBlank @Size(max = 100) @JsonProperty("device_id")
        @JsonDeserialize(using = DeviceIdDeserializer.class) String deviceId,
        @NotNull @JsonProperty("last_ping_at") Instant lastPingAt,
        @NotBlank @Size(max = 50) @JsonProperty("wifi_signal_rssi") String wifiSignalRssi,
        @NotBlank @Size(max = 100) @JsonProperty("firmware_version") String firmwareVersion,
        @NotBlank @Size(max = 50) @JsonProperty("connectivity_status") String connectivityStatus,
        @Size(max = 500) @JsonProperty("unavailability_reason") String unavailabilityReason
) {}
