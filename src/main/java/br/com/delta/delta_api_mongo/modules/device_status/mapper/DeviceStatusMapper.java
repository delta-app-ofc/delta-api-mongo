package br.com.delta.delta_api_mongo.modules.device_status.mapper;

import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import br.com.delta.delta_api_mongo.modules.device_status.dto.request.DeviceStatusRequest;
import br.com.delta.delta_api_mongo.modules.device_status.dto.response.DeviceStatusResponse;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class DeviceStatusMapper {
    public DeviceStatusDocument toDocument(DeviceStatusRequest request, String validatedDeviceId) {
        return new DeviceStatusDocument(null, validatedDeviceId, Instant.ofEpochMilli(request.lastPingAt().toEpochMilli()),
                request.wifiSignalRssi(), request.firmwareVersion(), request.connectivityStatus(), request.unavailabilityReason());
    }

    public DeviceStatusResponse toResponse(DeviceStatusDocument document) {
        return new DeviceStatusResponse(document.getId().toHexString(), document.getDeviceId(), document.getLastPingAt(),
                document.getWifiSignalRssi(), document.getFirmwareVersion(), document.getConnectivityStatus(),
                document.getUnavailabilityReason());
    }
}
