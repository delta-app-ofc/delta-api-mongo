package br.com.delta.delta_api_mongo.modules.alerts.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

public record AlertResponse(
        @JsonProperty("_id") String id,
        @JsonProperty("device_id") String deviceId,
        @JsonProperty("property_id") Integer propertyId,
        @JsonProperty("alert_type") String alertType,
        String severity,
        String title,
        String message,
        String source,
        String rule,
        Map<String, Object> evidence,
        @JsonProperty("triggered_at") Instant triggeredAt,
        @JsonProperty("resolved_at") Instant resolvedAt,
        @JsonProperty("dismissed_at") Instant dismissedAt
) {
}
