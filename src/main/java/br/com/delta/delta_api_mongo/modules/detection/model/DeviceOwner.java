package br.com.delta.delta_api_mongo.modules.detection.model;

import java.util.List;

/** Quem é o dono do dispositivo, vindo da delta-api-postgres. */
public record DeviceOwner(Integer propertyId, String classificationGroup, List<Integer> userIds,
                          BusinessSchedule schedule) {

    public boolean isResidential() {
        return "RESIDENCIAL".equals(classificationGroup);
    }

    public boolean isCommercial() {
        return "COMERCIAL".equals(classificationGroup);
    }
}
