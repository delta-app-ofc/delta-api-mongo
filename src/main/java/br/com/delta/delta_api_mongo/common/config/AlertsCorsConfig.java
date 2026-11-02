package br.com.delta.delta_api_mongo.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import java.util.Arrays;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class AlertsCorsConfig {
    @Bean
    FilterRegistrationBean<CorsFilter> alertsCors(@Value("${delta.web.allowed-origins:http://localhost:5173}") String allowed) {
        var origins = Arrays.stream(allowed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (origins.contains("*")) throw new IllegalArgumentException("Configure origens explícitas para a web.");
        var config = new CorsConfiguration();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "PUT", "PATCH", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/alerts/**", config);
        source.registerCorsConfiguration("/api/detection/**", config);
        var bean = new FilterRegistrationBean<>(new CorsFilter(source));
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return bean;
    }
}
