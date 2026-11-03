package br.com.delta.delta_api_mongo.modules.alerts.controller;

import br.com.delta.delta_api_mongo.modules.alerts.dto.request.CreateAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.dto.request.ResolveAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.ResolveAlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.service.AlertsService;
import br.com.delta.delta_api_mongo.modules.alerts.swagger.InternalAlertsSwagger;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/internal/alerts")
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class InternalAlertsController implements InternalAlertsSwagger {
    private final AlertsService service;

    public InternalAlertsController(AlertsService service) {
        this.service = service;
    }

    @Override
    @PostMapping
    public ResponseEntity<AlertResponse> create(@Valid @RequestBody CreateAlertRequest request) {
        var result = service.create(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.alert());
    }

    @Override
    @PostMapping("/resolve")
    public ResolveAlertResponse resolve(@Valid @RequestBody ResolveAlertRequest request) {
        return service.resolve(request.dedupKey());
    }
}
