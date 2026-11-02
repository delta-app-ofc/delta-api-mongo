package br.com.delta.delta_api_mongo.modules.alerts.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceAlreadyExistsException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.alerts.dto.request.CreateAlertRequest;
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

    private static CreateAlertRequest request(String source, String type, String severity, String dedupKey) {
        return new CreateAlertRequest(212, type, severity, "Título", "Mensagem", source, dedupKey, 1, null,
                Map.of("projected_percent", 108), null);
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

    // ---------------- criar (IA / sistema) ----------------

    @Test
    void createsAGluhAlertWithTheTimestampAndSource() {
        when(repository.findByDedupKeyAndResolvedAtIsNull(any())).thenReturn(List.of());
        when(repository.insert(any(AlertHistoryDocument.class))).thenAnswer(invocation -> {
            AlertHistoryDocument doc = invocation.getArgument(0);
            doc.setId(new ObjectId());
            return doc;
        });

        var result = service.create(request("gluh", "previsao_ultrapassagem_meta", "medium",
                "gluh:212:previsao_ultrapassagem_meta:2026-11"));

        assertThat(result.created()).isTrue();
        assertThat(result.alert().source()).isEqualTo("gluh");
        assertThat(result.alert().triggeredAt()).isEqualTo(NOW);
        assertThat(result.alert().resolvedAt()).isNull();
        assertThat(result.alert().evidence()).containsEntry("projected_percent", 108);
    }

    @Test
    void returnsTheExistingOpenAlertInsteadOfCreatingAnother() {
        var existing = alert(212, null, null);
        existing.setDedupKey("gluh:212:dica_personalizada:2026-11-02");
        when(repository.findByDedupKeyAndResolvedAtIsNull("gluh:212:dica_personalizada:2026-11-02"))
                .thenReturn(List.of(existing));

        var result = service.create(request("gluh", "dica_personalizada", "low", "gluh:212:dica_personalizada:2026-11-02"));

        assertThat(result.created()).isFalse();
        assertThat(result.alert().id()).isEqualTo(existing.getId().toHexString());
        verify(repository, never()).insert(any(AlertHistoryDocument.class));
    }

    @Test
    void aRaceOnTheUniqueIndexReturnsTheWinnerWithoutError() {
        var winner = alert(212, null, null);
        when(repository.findByDedupKeyAndResolvedAtIsNull("gluh:212:dica_personalizada:x"))
                .thenReturn(List.of()).thenReturn(List.of(winner));
        when(repository.insert(any(AlertHistoryDocument.class))).thenThrow(new DuplicateKeyException("dup"));

        var result = service.create(request("gluh", "dica_personalizada", "low", "gluh:212:dica_personalizada:x"));

        assertThat(result.created()).isFalse();
        assertThat(result.alert().id()).isEqualTo(winner.getId().toHexString());
    }

    @Test
    void theSameKeyOpenForAnotherUserIsAConflict() {
        var other = alert(999, null, null);
        when(repository.findByDedupKeyAndResolvedAtIsNull("system:tarifa_atualizada:2026-11")).thenReturn(List.of(other));

        assertThatThrownBy(() -> service.create(request("system", "tarifa_atualizada", "low",
                "system:tarifa_atualizada:2026-11"))).isInstanceOf(ResourceAlreadyExistsException.class);
    }

    @Test
    void highSeverityIsOnlyAllowedForTheLimitExceededAlertFromGluh() {
        when(repository.findByDedupKeyAndResolvedAtIsNull(any())).thenReturn(List.of());
        when(repository.insert(any(AlertHistoryDocument.class))).thenAnswer(invocation -> {
            AlertHistoryDocument doc = invocation.getArgument(0);
            doc.setId(new ObjectId());
            return doc;
        });

        assertThat(service.create(request("gluh", "limite_ultrapassado", "high", "gluh:212:limite_ultrapassado:2026-11"))
                .created()).isTrue();

        assertThatThrownBy(() -> service.create(request("gluh", "previsao_ultrapassagem_meta", "high",
                "gluh:212:previsao_ultrapassagem_meta:2026-11"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limite_ultrapassado");
        assertThatThrownBy(() -> service.create(request("system", "tarifa_atualizada", "high",
                "system:tarifa_atualizada:2026-11"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidSourceTypeSeverityAndDedupKey() {
        assertThatThrownBy(() -> service.create(request("detector", "vazamento_continuo", "high", "detector:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> service.create(request("gluh", "vazamento_continuo", "low", "gluh:212:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alert_type");
        assertThatThrownBy(() -> service.create(request("system", "dica_personalizada", "low", "system:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("alert_type");
        assertThatThrownBy(() -> service.create(request("gluh", "dica_personalizada", "urgent", "gluh:212:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("severity");
        // chave de outro usuário: o gluh só escreve para o user_id informado
        assertThatThrownBy(() -> service.create(request("gluh", "dica_personalizada", "low", "gluh:999:dica:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedup_key");
        assertThatThrownBy(() -> service.create(request("system", "tarifa_atualizada", "low", "gluh:212:x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dedup_key");
        verify(repository, never()).insert(any(AlertHistoryDocument.class));
    }

    @Test
    void rejectsEvidenceKeysThatMongoCouldReadAsOperators() {
        var withDollar = new CreateAlertRequest(212, "dica_personalizada", "low", "T", null, "gluh", "gluh:212:d",
                null, null, Map.of("$where", "1"), null);
        var nested = new CreateAlertRequest(212, "dica_personalizada", "low", "T", null, "gluh", "gluh:212:d",
                null, null, Map.of("ok", Map.of("a.b", 1)), null);

        assertThatThrownBy(() -> service.create(withDollar)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("$where");
        assertThatThrownBy(() -> service.create(nested)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------- encerrar ----------------

    @Test
    void internalWriterCannotResolveDetectorAlerts() {
        assertThatThrownBy(() -> service.resolve("detector:DEV-1:continuous_flow:212"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repository, never()).findByDedupKeyAndResolvedAtIsNull(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void resolveClosesTheOpenAlertAndIsIdempotent() {
        var open = alert(212, null, null);
        when(repository.findByDedupKeyAndResolvedAtIsNull("gluh:212:x")).thenReturn(List.of(open));
        when(repository.findByDedupKeyAndResolvedAtIsNull("gluh:212:y")).thenReturn(List.of());

        assertThat(service.resolve("gluh:212:x").resolved()).isEqualTo(1);
        assertThat(open.getResolvedAt()).isEqualTo(NOW);
        assertThat(service.resolve("gluh:212:y").resolved()).isZero();
        verify(repository).saveAll(List.of(open));
    }
}
