package br.com.delta.delta_api_mongo.common.exception;

import org.springframework.http.HttpStatus;

public class DeviceAuthenticationException extends RuntimeException {
    private final HttpStatus status;

    private DeviceAuthenticationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public static DeviceAuthenticationException unauthorized() {
        return new DeviceAuthenticationException(HttpStatus.UNAUTHORIZED, "Dispositivo não autenticado.");
    }

    public static DeviceAuthenticationException forbidden() {
        return new DeviceAuthenticationException(HttpStatus.FORBIDDEN, "Dispositivo sem permissão para gravar telemetria.");
    }

    public HttpStatus status() {
        return status;
    }
}
