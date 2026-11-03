package br.com.delta.delta_api_mongo.modules.alerts.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/** Alerta enviado pela IA (source gluh) ou por rotinas do sistema (source system) ao endpoint interno. */
public record CreateAlertRequest(
        @NotNull @Positive @JsonProperty("user_id") Integer userId,
        @NotBlank @Size(max = 60) @JsonProperty("alert_type") String alertType,
        @NotBlank @Size(max = 10) String severity,
        @NotBlank @Size(max = 120) String title,
        @Size(max = 500) String message,
        @NotBlank @Size(max = 20) String source,
        @NotBlank @Size(max = 200) @JsonProperty("dedup_key") String dedupKey,
        @Positive @JsonProperty("property_id") Integer propertyId,
        @Size(max = 100) @JsonProperty("device_id") String deviceId,
        Map<String, Object> evidence,
        @JsonProperty("triggered_at") Instant triggeredAt
) {
}
