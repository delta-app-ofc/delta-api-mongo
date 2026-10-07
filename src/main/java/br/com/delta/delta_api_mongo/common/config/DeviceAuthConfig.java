package br.com.delta.delta_api_mongo.common.config;

import br.com.delta.delta_api_mongo.common.deviceauth.SqlDeviceAuthClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeviceAuthProperties.class)
public class DeviceAuthConfig {
    @Bean
    SqlDeviceAuthClient sqlDeviceAuthClient(DeviceAuthProperties properties) {
        return new SqlDeviceAuthClient(properties);
    }
}
