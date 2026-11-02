package br.com.delta.delta_api_mongo.modules.detection.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registra as propriedades delta.detection.* (o detector começa desligado). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DetectionProperties.class)
public class DetectionConfig {
}
