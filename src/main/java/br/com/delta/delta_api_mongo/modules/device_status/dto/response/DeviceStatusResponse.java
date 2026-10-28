package br.com.delta.delta_api_mongo.modules.device_status.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record DeviceStatusResponse(
        @JsonProperty("_id") String id,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("last_ping_at") Instant lastPingAt,
        @JsonProperty("wifi_signal_rssi") String wifiSignalRssi,
        @JsonProperty("firmware_version") String firmwareVersion,
        @JsonProperty("connectivity_status") String connectivityStatus,
        @JsonProperty("battery_level") Integer batteryLevel,
        @JsonProperty("unavailability_reason") String unavailabilityReason
) {}
