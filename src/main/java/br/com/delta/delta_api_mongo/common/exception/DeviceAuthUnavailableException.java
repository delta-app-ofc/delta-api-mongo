package br.com.delta.delta_api_mongo.common.exception;

public class DeviceAuthUnavailableException extends RuntimeException {
    public DeviceAuthUnavailableException() {
        // Do not retain HTTP exceptions: they may contain headers or response bodies.
        super("Não foi possível validar o dispositivo. Tente novamente mais tarde.");
    }
}
