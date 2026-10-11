package br.com.delta.delta_api_mongo.common.exception;

public class DeviceRejectedException extends RuntimeException {
    public DeviceRejectedException() {
        super("Dispositivo não cadastrado ou inativo.");
    }
}
