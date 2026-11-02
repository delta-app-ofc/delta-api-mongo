package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.BusinessSchedule;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import static br.com.delta.delta_api_mongo.modules.detection.DetectionTestSupport.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T15:00:00Z");
    private static final DeviceOwner RESIDENCE = new DeviceOwner(10, "RESIDENCIAL", List.of(7, 30),
            new BusinessSchedule(List.of(), java.util.Set.of()));
    private AlertHistoryRepository repository;
    private AlertService service;
    private UserHourBaselineDocument state;

    @BeforeEach
    void setUp() {
        repository = mock(AlertHistoryRepository.class);
        service = new AlertService(repository, properties());
        state = new UserHourBaselineDocument("DEV-1", -1);
    }

    @SuppressWarnings("unchecked")
    private List<AlertHistoryDocument> savedAlerts() {
        ArgumentCaptor<Iterable<AlertHistoryDocument>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(repository).saveAll(captor.capture());
        var list = new java.util.ArrayList<AlertHistoryDocument>();
        captor.getValue().forEach(list::add);
        return list;
    }

    @Test
    void opensOneAlertPerUserOfTheProperty() {
        service.sync("DEV-1", Optional.of(RESIDENCE), List.of("continuous_flow"), Map.of("k", "v"), NOW, state);

        var alerts = savedAlerts();
        assertThat(alerts).extracting(AlertHistoryDocument::getUserId).containsExactly(7, 30);
        assertThat(alerts).allSatisfy(alert -> {
            assertThat(alert.getAlertType()).isEqualTo("vazamento_continuo");
            assertThat(alert.getSeverity()).isEqualTo("high");
            assertThat(alert.getRule()).isEqualTo("continuous_flow");
            assertThat(alert.getTriggeredAt()).isEqualTo(NOW);
            assertThat(alert.getResolvedAt()).isNull();
            assertThat(alert.getEvidence()).containsEntry("k", "v");
        });
    }

    @Test
    void doesNotOpenAnotherAlertWhileOneIsStillOpenForTheSameRule() {
        var open = new AlertHistoryDocument();
        when(repository.findByDeviceIdAndRuleAndResolvedAtIsNull("DEV-1", "continuous_flow")).thenReturn(List.of(open));

        service.sync("DEV-1", Optional.of(RESIDENCE), List.of("continuous_flow"), Map.of(), NOW, state);

        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    void anotherRuleOpensItsOwnAlertEvenWithAnotherOneOpen() {
        var open = new AlertHistoryDocument();
        when(repository.findByDeviceIdAndRuleAndResolvedAtIsNull("DEV-1", "continuous_flow")).thenReturn(List.of(open));

        service.sync("DEV-1", Optional.of(RESIDENCE), List.of("continuous_flow", "robust_zscore"), Map.of(), NOW, state);

        var alerts = savedAlerts();
        assertThat(alerts).extracting(AlertHistoryDocument::getRule).containsOnly("robust_zscore");
        assertThat(alerts).extracting(AlertHistoryDocument::getAlertType).containsOnly("fluxo_atipico");
    }

    @Test
    void flowOutsideBusinessHoursHasItsOwnAlertType() {
        var company = new DeviceOwner(11, "COMERCIAL", List.of(50), null);

        service.sync("DEV-2", Optional.of(company), List.of("off_hours_flow"), Map.of(), NOW, state);

        var alerts = savedAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getAlertType()).isEqualTo("consumo_fora_do_expediente");
        assertThat(AlertService.alertType("night_floor")).isEqualTo("vazamento_continuo");
    }

    @Test
    void createsNoAlertWhenTheOwnerIsUnknown() {
        service.sync("DEV-1", Optional.empty(), List.of("continuous_flow"), Map.of(), NOW, state);

        verify(repository, never()).saveAll(anyIterable());
    }

    @Test
    void closesTheAlertAfterEnoughConsecutiveNormalWindows() {
        var open = new AlertHistoryDocument();
        open.setDeviceId("DEV-1");
        when(repository.findByDeviceIdAndRuleAndResolvedAtIsNull("DEV-1", "continuous_flow")).thenReturn(List.of(open));

        for (int i = 0; i < 35; i++) service.sync("DEV-1", Optional.of(RESIDENCE), List.of(), Map.of(), NOW, state);
        assertThat(open.getResolvedAt()).isNull();

        service.sync("DEV-1", Optional.of(RESIDENCE), List.of(), Map.of(), NOW.plusSeconds(300), state);

        assertThat(open.getResolvedAt()).isEqualTo(NOW.plusSeconds(300));
        verify(repository).saveAll(any());
    }

    @Test
    void aNewFlaggedWindowRestartsTheCountToCloseTheAlert() {
        var open = new AlertHistoryDocument();
        when(repository.findByDeviceIdAndRuleAndResolvedAtIsNull("DEV-1", "continuous_flow")).thenReturn(List.of(open));

        for (int i = 0; i < 35; i++) service.sync("DEV-1", Optional.of(RESIDENCE), List.of(), Map.of(), NOW, state);
        service.sync("DEV-1", Optional.of(RESIDENCE), List.of("continuous_flow"), Map.of(), NOW, state);
        service.sync("DEV-1", Optional.of(RESIDENCE), List.of(), Map.of(), NOW, state);

        assertThat(open.getResolvedAt()).isNull();
    }
}
