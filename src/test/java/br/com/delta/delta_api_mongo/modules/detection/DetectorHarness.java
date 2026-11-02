package br.com.delta.delta_api_mongo.modules.detection;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import br.com.delta.delta_api_mongo.modules.detection.config.DetectionProperties;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import br.com.delta.delta_api_mongo.modules.detection.service.AlertService;
import br.com.delta.delta_api_mongo.modules.detection.service.AnomalyDetector;
import br.com.delta.delta_api_mongo.modules.detection.service.BaselineService;
import br.com.delta.delta_api_mongo.modules.detection.service.CohortBaseline;
import br.com.delta.delta_api_mongo.modules.detection.service.DeviceOwnerResolver;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Detector completo com bancos em memória: permite simular dias de consumo sem Mongo.
 * Usado pelos testes do detector e pelos cenários sintéticos de calibração.
 */
public class DetectorHarness {
    public final List<ConsumptionSummaryDocument> windows = new ArrayList<>();
    public final Map<String, UserHourBaselineDocument> baselines = new HashMap<>();
    public final List<AlertHistoryDocument> alerts = new ArrayList<>();
    public final Optional<DeviceOwner>[] owner;
    public final AnomalyDetector detector;
    public final DetectionProperties properties;

    @SuppressWarnings("unchecked")
    public DetectorHarness(DetectionProperties properties, Optional<DeviceOwner> ownerOrEmpty) {
        this.properties = properties;
        this.owner = new Optional[]{ownerOrEmpty};

        var consumptions = mock(ConsumptionSummaryRepository.class);
        when(consumptions.findRecentByDevice(anyString(), any(Instant.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    String device = invocation.getArgument(0);
                    Instant until = invocation.getArgument(1);
                    Pageable page = invocation.getArgument(2);
                    // As janelas são adicionadas em ordem cronológica: basta andar do fim para o começo.
                    var found = new ArrayList<ConsumptionSummaryDocument>();
                    for (int i = windows.size() - 1; i >= 0 && found.size() < page.getPageSize(); i--) {
                        var w = windows.get(i);
                        if (w.getDeviceId().equals(device) && !w.getWindowStartedAt().isAfter(until)) found.add(w);
                    }
                    return found;
                });

        var baselineRepository = mock(UserHourBaselineRepository.class);
        when(baselineRepository.findByDeviceIdAndHourOfWeek(anyString(), anyInt())).thenAnswer(invocation ->
                Optional.ofNullable(baselines.get(invocation.getArgument(0) + "#" + invocation.getArgument(1))));
        when(baselineRepository.save(any(UserHourBaselineDocument.class))).thenAnswer(invocation -> {
            UserHourBaselineDocument doc = invocation.getArgument(0);
            baselines.put(doc.getDeviceId() + "#" + doc.getHourOfWeek(), doc);
            return doc;
        });
        when(baselineRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<UserHourBaselineDocument> docs = invocation.getArgument(0);
            docs.forEach(doc -> baselines.put(doc.getDeviceId() + "#" + doc.getHourOfWeek(), doc));
            return List.of();
        });
        when(baselineRepository.findByDeviceIdAndHourOfWeekGreaterThanEqual(anyString(), anyInt())).thenAnswer(
                invocation -> baselines.values().stream()
                        .filter(doc -> doc.getDeviceId().equals(invocation.getArgument(0))
                                && doc.getHourOfWeek() >= (int) invocation.getArgument(1)).toList());
        when(baselineRepository.findByHourOfWeekAndCountGreaterThanEqual(anyInt(), anyInt(), any(Pageable.class)))
                .thenReturn(List.of());

        var alertRepository = mock(AlertHistoryRepository.class);
        when(alertRepository.findByDeviceIdAndRuleAndResolvedAtIsNull(anyString(), anyString())).thenAnswer(
                invocation -> alerts.stream().filter(a -> a.getDeviceId().equals(invocation.getArgument(0))
                        && invocation.getArgument(1).equals(a.getRule()) && a.getResolvedAt() == null).toList());
        when(alertRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<AlertHistoryDocument> docs = invocation.getArgument(0);
            docs.forEach(doc -> {
                if (doc.getId() == null) {
                    doc.setId(new ObjectId());
                    alerts.add(doc);
                }
            });
            return List.of();
        });

        var owners = mock(DeviceOwnerResolver.class);
        when(owners.resolve(anyString())).thenAnswer(invocation -> this.owner[0]);

        var baselineService = new BaselineService(baselineRepository, properties);
        var cohort = new CohortBaseline(baselineRepository, properties);
        var alertService = new AlertService(alertRepository, properties);
        this.detector = new AnomalyDetector(properties, consumptions, baselineService, cohort, owners, alertService);
    }

    public void setOwner(Optional<DeviceOwner> newOwner) {
        owner[0] = newOwner;
    }

    /** Grava a janela (como o POST faria) e roda o detector sobre ela. */
    public DetectionResult feed(String deviceId, Instant start, double liters, double lpm) {
        var doc = new ConsumptionSummaryDocument(new ObjectId(), deviceId, start, start.plusSeconds(300), liters, lpm);
        windows.add(doc);
        return detector.evaluate(doc);
    }

    public long openAlerts() {
        return alerts.stream().filter(a -> a.getResolvedAt() == null).count();
    }
}
