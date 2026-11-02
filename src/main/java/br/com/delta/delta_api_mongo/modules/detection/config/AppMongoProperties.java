package br.com.delta.delta_api_mongo.modules.detection.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Conexão com o banco da aplicação (db_delta_app), usada pelo baseline e pelos alertas. */
@Validated
@ConfigurationProperties(prefix = "delta.mongodb.app")
public record AppMongoProperties(
        @NotBlank String uri,
        @NotBlank String database
) {
}
