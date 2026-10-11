package br.com.delta.delta_api_mongo.modules.consumption.controller;

import br.com.delta.delta_api_mongo.modules.consumption.dto.request.ConsumptionSummaryRequest;
import br.com.delta.delta_api_mongo.modules.consumption.dto.response.ConsumptionSummaryResponse;
import br.com.delta.delta_api_mongo.modules.consumption.service.ConsumptionSummaryService;
import br.com.delta.delta_api_mongo.modules.consumption.swagger.ConsumptionSummarySwagger;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import java.time.Instant;

@RestController
@RequestMapping("/api/telemetry/consumption-summary")
@RequiredArgsConstructor
public class ConsumptionSummaryController implements ConsumptionSummarySwagger {
    private final ConsumptionSummaryService service;

    @Override
    @PostMapping
    public ResponseEntity<ConsumptionSummaryResponse> create(@Valid @RequestBody ConsumptionSummaryRequest request) {
        var response = service.create(request);
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}")
                .buildAndExpand(response.id()).toUri();
        return ResponseEntity.created(location).body(response);
    }

    @Override
    @GetMapping("/{id}")
    public ConsumptionSummaryResponse findById(@PathVariable("id") String id) {
        return service.findById(id);
    }

    @Override
    @GetMapping
    public PagedModel<ConsumptionSummaryResponse> findByDeviceAndPeriod(
            @RequestParam("device_id") String deviceId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant start,
            @RequestParam("end") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant end,
            @PageableDefault(size = 20) Pageable pageable) {
        return new PagedModel<>(service.findByDeviceAndPeriod(deviceId, start, end, pageable));
    }
}
