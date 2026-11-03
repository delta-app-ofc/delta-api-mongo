package br.com.delta.delta_api_mongo.modules.alerts.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceAlreadyExistsException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.mapper.AlertMapper;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AlertsServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-02T12:00:00Z");
    private AlertHistoryRepository repository;
    private AlertsService service;

    @BeforeEach
    void setUp() {
        repository = mock(AlertHistoryRepository.class);
        service = new AlertsService(repository, new AlertMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static AlertHistoryDocument alert(Integer userId, String deviceId, Double litersPerDay) {
        var alert = new AlertHistoryDocument();
        alert.setId(new ObjectId());
        alert.setUserId(userId);
        alert.setDeviceId(deviceId);
        alert.setAlertType("vazamento_continuo");
        alert.setTriggeredAt(NOW.minusSeconds(3600));
        if (litersPerDay != null) alert.setEvidence(Map.of("liters_per_day_estimate", litersPerDay));
        return alert;
    }

    // ---------------- listar ----------------

    @Test
    void listsOnlyVisibleAlertsOfTheUserByDefault() {
        when(repository.findVisibleByUser(eq(212), any())).thenReturn(new PageImpl<>(List.of(alert(212, "D1", null))));

        var page = service.list(212, null, false, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        verify(repository, never()).findAllByUser(any(), any());
    }

    @Test
    void severityAndIncludeDismissedPickTheRightQuery() {
        when(repository.findVisibleByUserAndSeverity(eq(212), eq("high"), any())).thenReturn(new PageImpl<>(List.of()));
        when(repository.findAllByUserAndSeverity(eq(212), eq("low"), any())).thenReturn(new PageImpl<>(List.of()));
        when(repository.findAllByUser(eq(212), any())).thenReturn(new PageImpl<>(List.of()));

        service.list(212, "high", false, PageRequest.of(0, 20));
        service.list(212, "low", true, PageRequest.of(0, 20));
        service.list(212, null, true, PageRequest.of(0, 20));

        verify(repository).findVisibleByUserAndSeverity(eq(212), eq("high"), any());
        verify(repository).findAllByUserAndSeverity(eq(212), eq("low"), any());
        verify(repository).findAllByUser(eq(212), any());
    }

    @Test
    void rejectsAnUnknownSeverityAndOversizedOrUnpagedRequests() {
        assertThatThrownBy(() -> service.list(212, "critical", false, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("severity");
        assertThatThrownBy(() -> service.list(212, null, false, PageRequest.of(0, 101)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
        assertThatThrownBy(() -> service.list(212, null, false, org.springframework.data.domain.Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- dispensar ----------------

    @Test
    void dismissSetsTheTimestampOnlyOnTheOwnersAlert() {
        var mine = alert(212, "D1", null);
        when(repository.findById(mine.getId())).thenReturn(java.util.Optional.of(mine));

        var response = service.dismiss(212, mine.getId().toHexString());

        assertThat(response.dismissedAt()).isEqualTo(NOW);
        verify(repository).save(mine);
    }

    @Test
    void dismissIsIdempotentAndKeepsTheFirstTimestamp() {
        var mine = alert(212, "D1", null);
        mine.setDismissedAt(NOW.minusSeconds(600));
        when(repository.findById(mine.getId())).thenReturn(java.util.Optional.of(mine));

        var response = service.dismiss(212, mine.getId().toHexString());

        assertThat(response.dismissedAt()).isEqualTo(NOW.minusSeconds(600));
        verify(repository, never()).save(any());
    }

    @Test
    void anotherUsersAlertLooksLikeItDoesNotExist() {
        var theirs = alert(999, "D1", null);
        when(repository.findById(theirs.getId())).thenReturn(java.util.Optional.of(theirs));

        assertThatThrownBy(() -> service.dismiss(212, theirs.getId().toHexString()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void dismissRejectsAnInvalidIdAndReportsAMissingAlert() {
        assertThatThrownBy(() -> service.dismiss(212, "xyz")).isInstanceOf(IllegalArgumentException.class);
        var id = new ObjectId();
        when(repository.findById(id)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> service.dismiss(212, id.toHexString())).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------------- resumo de vazamentos ----------------

    @Test
    void leakSummaryCountsEachDeviceOnceWithItsHighestEstimate() {
        when(repository.findLeakAlerts(eq(212), eq(NOW.minusSeconds(24 * 3600)))).thenReturn(List.of(
                alert(212, "D1", 1000.0), alert(212, "D1", 1500.0), alert(212, "D2", 400.0)));

        var summary = service.leakSummary(212);

        assertThat(summary.litersPerDay()).isEqualTo(1900);
        assertThat(summary.points()).isEqualTo(2);
        assertThat(summary.windowHours()).isEqualTo(24);
        assertThat(summary.since()).isEqualTo(NOW.minusSeconds(24 * 3600));
    }

    @Test
    void leakSummaryIsZeroWithoutLeaksAndIgnoresAlertsWithoutEstimate() {
        when(repository.findLeakAlerts(eq(212), any())).thenReturn(List.of());
        assertThat(service.leakSummary(212).points()).isZero();

        when(repository.findLeakAlerts(eq(7), any())).thenReturn(List.of(alert(7, "D1", null)));
        var summary = service.leakSummary(7);
        assertThat(summary.litersPerDay()).isZero();
        assertThat(summary.points()).isEqualTo(1);
    }
}
