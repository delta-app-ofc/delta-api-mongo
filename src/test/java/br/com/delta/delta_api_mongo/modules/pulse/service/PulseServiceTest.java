package br.com.delta.delta_api_mongo.modules.pulse.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.pulse.document.PulseDocument;
import br.com.delta.delta_api_mongo.modules.pulse.dto.request.PulseRequest;
import br.com.delta.delta_api_mongo.modules.pulse.mapper.PulseMapper;
import br.com.delta.delta_api_mongo.modules.pulse.repository.PulseRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PulseServiceTest {

    private static final Instant SENT_AT = Instant.parse("2026-09-27T17:13:06Z");
    private static final ObjectId ID = new ObjectId("6ab94eaad79d853341d2605c");

    @Mock
    private PulseRepository repository;

    private PulseService service;

    @BeforeEach
    void setUp() {
        service = new PulseService(repository, new PulseMapper());
    }

    @Test
    void savesBatchAndReturnsGeneratedIdWithPulseData() {
        var pulse = new PulseRequest.PulseItemRequest(SENT_AT.minusSeconds(30), 31100411L, 0L);
        var request = new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 1, List.of(pulse));
        when(repository.save(any(PulseDocument.class))).thenAnswer(invocation -> {
            PulseDocument document = invocation.getArgument(0);
            assertThat(document.getId()).isNull();
            document.setId(ID);
            return document;
        });

        var response = service.create(request);

        assertThat(response.id()).isEqualTo(ID.toHexString());
        assertThat(response.deviceId()).isEqualTo(request.deviceId());
        assertThat(response.sentAt()).isEqualTo(SENT_AT);
        assertThat(response.windowMinutes()).isEqualTo(5);
        assertThat(response.totalPulses()).isEqualTo(1);
        assertThat(response.pulses()).singleElement().satisfies(item -> {
            assertThat(item.pulsedAt()).isEqualTo(pulse.pulsedAt());
            assertThat(item.msSinceBoot()).isEqualTo(pulse.msSinceBoot());
            assertThat(item.deltaMs()).isZero();
        });
    }

    @Test
    void acceptsWindowWithoutPulses() {
        when(repository.save(any(PulseDocument.class))).thenAnswer(invocation -> {
            PulseDocument document = invocation.getArgument(0);
            document.setId(ID);
            return document;
        });

        assertThat(service.create(new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 0, List.of())).pulses())
                .isEmpty();
    }

    @Test
    void rejectsIncorrectPulseCountBeforeSaving() {
        var request = new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 1, List.of());

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("total_pulses");
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsPulsesOutsideWindowBeforeSaving() {
        for (Instant time : List.of(SENT_AT.minusSeconds(301), SENT_AT.plusSeconds(1))) {
            var pulse = new PulseRequest.PulseItemRequest(time, 1000L, 0L);
            var request = new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 1, List.of(pulse));

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("janela");
        }
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsOutOfOrderPulsesBeforeSaving() {
        var later = new PulseRequest.PulseItemRequest(SENT_AT.minusSeconds(10), 2000L, 0L);
        var earlier = new PulseRequest.PulseItemRequest(SENT_AT.minusSeconds(20), 1000L, 0L);
        var request = new PulseRequest("ESP32-SP-2929", SENT_AT, 5, 2, List.of(later, earlier));

        assertThatThrownBy(() -> service.create(request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ordem cronológica");
        verifyNoInteractions(repository);
    }

    @Test
    void distinguishesInvalidIdFromMissingBatch() {
        assertThatThrownBy(() -> service.findById("invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);

        when(repository.findById(ID)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findById(ID.toHexString()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsInvalidQueryPeriodBeforeAccessingRepository() {
        assertThatThrownBy(() -> service.findByDeviceAndPeriod(
                "ESP32-SP-2929", SENT_AT, SENT_AT, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void mapsQueryResultsPreservingPagination() {
        var start = SENT_AT.minusSeconds(3600);
        var pageable = PageRequest.of(0, 20);
        var document = new PulseDocument(ID, "ESP32-SP-2929", SENT_AT.minusSeconds(60), 5, 0, List.of());
        when(repository.findByDeviceIdAndSentAtGreaterThanEqualAndSentAtLessThanOrderBySentAtDesc(
                "ESP32-SP-2929", start, SENT_AT, pageable))
                .thenReturn(new PageImpl<>(List.of(document), pageable, 25));

        var result = service.findByDeviceAndPeriod("ESP32-SP-2929", start, SENT_AT, pageable);

        assertThat(result.getTotalElements()).isEqualTo(25);
        assertThat(result.getTotalPages()).isEqualTo(2);
        assertThat(result.getContent()).singleElement()
                .satisfies(response -> assertThat(response.id()).isEqualTo(ID.toHexString()));
    }
}
