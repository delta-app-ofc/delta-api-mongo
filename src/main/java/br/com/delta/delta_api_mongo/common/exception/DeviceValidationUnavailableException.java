package br.com.delta.delta_api_mongo.common.exception;

public class DeviceValidationUnavailableException extends RuntimeException {
    public DeviceValidationUnavailableException() {
        // Do not retain HTTP exceptions: they may contain headers or response bodies.
        super("Não foi possível validar o dispositivo. Tente novamente mais tarde.");
    }
}
