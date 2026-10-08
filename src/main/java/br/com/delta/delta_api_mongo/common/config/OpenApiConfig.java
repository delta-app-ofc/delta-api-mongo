package br.com.delta.delta_api_mongo.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration
@SecurityScheme(name = "deviceApiKey", type = SecuritySchemeType.HTTP, scheme = "bearer",
        description = "API key delta_dev_ do dispositivo, emitida pela SQL")
@OpenAPIDefinition(
        info = @Info(
                title = "Delta Mongo API",
                version = "1.0.0",
                description = "API de telemetria e dados documentais da plataforma Delta"
        )
)
public class OpenApiConfig {
}
