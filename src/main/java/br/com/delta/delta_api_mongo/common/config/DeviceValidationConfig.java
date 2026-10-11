package br.com.delta.delta_api_mongo.common.config;

import br.com.delta.delta_api_mongo.common.devicevalidation.SqlDeviceValidationClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceValidationProperties.class)
public class DeviceValidationConfig {
    @Bean
    SqlDeviceValidationClient sqlDeviceValidationClient(DeviceValidationProperties properties) {
        return new SqlDeviceValidationClient(properties);
    }
}
