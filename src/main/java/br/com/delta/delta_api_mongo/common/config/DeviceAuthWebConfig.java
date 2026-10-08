package br.com.delta.delta_api_mongo.common.config;

import br.com.delta.delta_api_mongo.common.deviceauth.DeviceAuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class DeviceAuthWebConfig implements WebMvcConfigurer {
    private final DeviceAuthInterceptor interceptor;

    public DeviceAuthWebConfig(DeviceAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Match the actual handler annotation, including any aliases of the route.
        registry.addInterceptor(interceptor);
    }
}
