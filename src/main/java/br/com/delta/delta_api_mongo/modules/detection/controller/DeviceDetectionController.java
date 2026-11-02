package br.com.delta.delta_api_mongo.modules.detection.controller;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.security.UserJwtFilter;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import br.com.delta.delta_api_mongo.modules.detection.service.AnomalyDetector;
import br.com.delta.delta_api_mongo.modules.detection.service.DeviceOwnerResolver;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/detection/devices/{deviceId}")
@Tag(name = "Detecção", description = "Contexto e evidências do dispositivo; exige JWT e vínculo atual com o imóvel.")
@ConditionalOnExpression("${delta.detection.enabled:false} and ${delta.alerts.enabled:false}")
@RequiredArgsConstructor
public class DeviceDetectionController {
    private final DeviceOwnerResolver owners;
    private final AnomalyDetector detector;
    private final ConsumptionSummaryRepository consumption;
    private final Clock clock;

    public record VisitRequest(@JsonProperty("visit_until") Instant until) {}
    public record VisitResponse(@JsonProperty("visit_until") Instant until, @JsonProperty("updated_at") Instant updatedAt) {}
    public record WindowEvidence(@JsonProperty("window_started_at") Instant start,
                                 @JsonProperty("window_finished_at") Instant end,
                                 @JsonProperty("consumption_liters") Double liters,
                                 @JsonProperty("lpm_average") Double lpm,
                                 @JsonProperty("anomaly_detected") Boolean anomaly,
                                 @JsonProperty("anomaly_reasons") List<String> reasons,
                                 @JsonProperty("anomaly_evidence") Map<String, Object> evidence,
                                 @JsonProperty("detection_status") String status) {}

    private Integer authorize(String deviceId, HttpServletRequest request) {
        Integer user = (Integer) request.getAttribute(UserJwtFilter.USER_ID);
        var owner = owners.fresh(deviceId).filter(found -> user != null && found.userIds().contains(user))
                .orElseThrow(() -> new ResourceNotFoundException("Dispositivo não encontrado."));
        return user;
    }

    @PutMapping("/visit")
    @Operation(summary = "Declarar visita temporária; visit_until nulo cancela. Não desativa regras de vazamento.")
    public VisitResponse visit(@PathVariable String deviceId, @Valid @RequestBody VisitRequest body,
                               HttpServletRequest request) {
        Integer user = authorize(deviceId, request);
        Instant now = clock.instant();
        if (body.until() != null && (!body.until().isAfter(now) || body.until().isAfter(now.plus(Duration.ofDays(90))))) {
            throw new IllegalArgumentException("visit_until deve estar no futuro, até 90 dias.");
        }
        detector.declareVisit(deviceId, body.until(), user, now);
        return new VisitResponse(body.until(), now);
    }

    @GetMapping("/evidence")
    @Operation(summary = "Série medida e evidências persistidas do baseline; até 7 dias, 100 janelas por página.")
    public PagedModel<WindowEvidence> evidence(@PathVariable String deviceId, @RequestParam Instant start,
            @RequestParam Instant end, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size, HttpServletRequest request) {
        authorize(deviceId, request);
        if (!start.isBefore(end) || Duration.between(start, end).compareTo(Duration.ofDays(7)) > 0
                || page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("Período ou paginação inválidos.");
        var windows = consumption.findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
                deviceId, start, end, PageRequest.of(page, size));
        return new PagedModel<>(windows.map(w -> new WindowEvidence(w.getWindowStartedAt(), w.getWindowFinishedAt(),
                w.getConsumptionLiters(), w.getLpmAverage(), w.getAnomalyDetected(), w.getAnomalyReasons(),
                w.getAnomalyEvidence(), w.getDetectionStatus())));
    }
}
