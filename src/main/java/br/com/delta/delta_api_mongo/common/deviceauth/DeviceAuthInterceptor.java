package br.com.delta.delta_api_mongo.common.deviceauth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Collections;
import java.util.regex.Pattern;

@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {
    public static final String DEVICE_ATTRIBUTE = "delta.authenticatedDevice";
    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) (delta_dev_[A-Za-z0-9_-]+)");
    private final SqlDeviceAuthClient client;

    public DeviceAuthInterceptor(SqlDeviceAuthClient client) {
        this.client = client;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method) || !method.hasMethodAnnotation(DeviceIngestion.class)) {
            return true;
        }
        var headers = Collections.list(request.getHeaders("Authorization"));
        if (headers.size() != 1 || headers.get(0).length() > 263) {
            throw DeviceAuthenticationException.unauthorized();
        }
        var bearer = BEARER.matcher(headers.get(0));
        if (!bearer.matches()) {
            throw DeviceAuthenticationException.unauthorized();
        }
        var device = client.validate(bearer.group(1)).orElseThrow(DeviceAuthenticationException::unauthorized);
        if (!device.permissions().contains("telemetry:write")) {
            throw DeviceAuthenticationException.forbidden();
        }
        request.setAttribute(DEVICE_ATTRIBUTE, device);
        return true;
    }
}
