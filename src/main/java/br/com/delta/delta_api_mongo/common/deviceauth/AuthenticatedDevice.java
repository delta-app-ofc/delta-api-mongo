package br.com.delta.delta_api_mongo.common.deviceauth;

import java.util.List;

/** Identity supplied by SQL; never contains credentials. */
public record AuthenticatedDevice(String deviceId, long credentialId, List<String> permissions) {
    public AuthenticatedDevice {
        permissions = List.copyOf(permissions);
    }
}
