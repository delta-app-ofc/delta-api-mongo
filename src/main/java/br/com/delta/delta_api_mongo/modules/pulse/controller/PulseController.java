package br.com.delta.delta_api_mongo.modules.pulse.controller;

import br.com.delta.delta_api_mongo.common.deviceauth.AuthenticatedDevice;
import br.com.delta.delta_api_mongo.common.deviceauth.DeviceAuthInterceptor;
import br.com.delta.delta_api_mongo.common.deviceauth.DeviceIngestion;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.dto.response.PulseResponse;
import br.com.delta.delta_api_mongo.modules.pulse.service.PulseService;
import br.com.delta.delta_api_mongo.modules.pulse.swagger.PulseSwagger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.time.Instant;

@RestController
@RequestMapping("/api/telemetry/pulses")
@RequiredArgsConstructor
public class PulseController implements PulseSwagger {

    private final PulseService service;

    @Override
    @PostMapping
    @DeviceIngestion
    public ResponseEntity<PulseResponse> create(
            @Valid @RequestBody PulseRequest request,
            @RequestAttribute(DeviceAuthInterceptor.DEVICE_ATTRIBUTE) AuthenticatedDevice device) {
        PulseResponse response = service.create(request, device);
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Override
    @GetMapping("/{id}")
    public PulseResponse findById(@PathVariable("id") String id) {
        return service.findById(id);
    }

    @Override
    @GetMapping
    public PagedModel<PulseResponse> findByDeviceAndPeriod(
            @RequestParam("device_id") String deviceId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @PageableDefault(size = 20) Pageable pageable
    ) {
        return new PagedModel<>(service.findByDeviceAndPeriod(deviceId, start, end, pageable));
    }
}
