package br.com.delta.delta_api_mongo.common.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "delta.mongodb.telemetry")
public record TelemetryMongoProperties(
        @NotBlank String uri,
        @NotBlank String database
) {
}
