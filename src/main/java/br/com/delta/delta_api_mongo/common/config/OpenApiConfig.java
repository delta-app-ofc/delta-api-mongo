package br.com.delta.delta_api_mongo.common.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Delta Mongo API",
                version = "1.0.0",
                description = "API de telemetria e dados documentais da plataforma Delta"
        )
)
public class OpenApiConfig {
}
