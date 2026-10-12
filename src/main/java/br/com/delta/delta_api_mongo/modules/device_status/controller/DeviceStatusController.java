package br.com.delta.delta_api_mongo.modules.device_status.controller;

import br.com.delta.delta_api_mongo.modules.device_status.dto.request.DeviceStatusRequest;
import br.com.delta.delta_api_mongo.modules.device_status.dto.response.DeviceStatusResponse;
import br.com.delta.delta_api_mongo.modules.device_status.service.DeviceStatusService;
import br.com.delta.delta_api_mongo.modules.device_status.swagger.DeviceStatusSwagger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/telemetry/device-status")
@RequiredArgsConstructor
public class DeviceStatusController implements DeviceStatusSwagger {
    private final DeviceStatusService service;

    @Override
    @PostMapping
    public DeviceStatusResponse receive(@Valid @RequestBody DeviceStatusRequest request) {
        return service.receive(request);
    }

    @Override
    @GetMapping
    public DeviceStatusResponse findByDeviceId(@RequestParam("device_id") String deviceId) {
        return service.findByDeviceId(deviceId);
    }
}
