package br.com.delta.delta_api_mongo.modules.consumption.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceAlreadyExistsException;
import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.dto.request.ConsumptionSummaryRequest;
import br.com.delta.delta_api_mongo.modules.consumption.mapper.ConsumptionSummaryMapper;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryIndexes;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumptionSummaryServiceTest {
    private static final String DEVICE_ID = "ESP32-SP-0912";
    private static final Instant START = Instant.parse("2026-07-17T17:10:00Z");
    private static final Instant END = START.plusSeconds(300);

    private ConsumptionSummaryRepository repository;
    private ConsumptionSummaryIndexes indexes;
    private ConsumptionSummaryService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConsumptionSummaryRepository.class);
        indexes = mock(ConsumptionSummaryIndexes.class);
        service = new ConsumptionSummaryService(repository, new ConsumptionSummaryMapper(), indexes);
    }

    @Test
    void createEnsuresIndexesAndReturnsInsertedSummary() {
        var inserted = document();
        when(repository.insert(any(ConsumptionSummaryDocument.class))).thenReturn(inserted);

        var result = service.create(request(12.5, 2.5));

        assertThat(result.id()).isEqualTo(inserted.getId().toHexString());
        assertThat(result.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(result.consumptionLiters()).isEqualTo(12.5);
        verify(indexes).ensureCreated();
    }

    @Test
    void createRejectsAnInvalidWindowBeforeTouchingMongo() {
        assertThatThrownBy(() -> service.create(new ConsumptionSummaryRequest(
                DEVICE_ID, END, START, 12.5, 2.5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anterior");

        verify(indexes, never()).ensureCreated();
        verify(repository, never()).insert(any(ConsumptionSummaryDocument.class));
    }

    @Test
    void createRejectsNonFiniteConsumptionAndFlow() {
        assertThatThrownBy(() -> service.create(request(Double.NaN, 2.5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create(request(12.5, Double.POSITIVE_INFINITY)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).insert(any(ConsumptionSummaryDocument.class));
    }

    @Test
    void createMapsDuplicateDeviceWindowToConflict() {
        when(repository.insert(any(ConsumptionSummaryDocument.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(request(12.5, null)))
                .isInstanceOf(ResourceAlreadyExistsException.class)
                .hasMessageContaining("Já existe");
    }

    @Test
    void findByIdRejectsInvalidObjectIdWithoutQueryingRepository() {
        assertThatThrownBy(() -> service.findById("not-an-object-id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ObjectId");

        verify(repository, never()).findById(any(ObjectId.class));
    }

    @Test
    void findByIdMapsExistingDocument() {
        var document = document();
        when(repository.findById(document.getId())).thenReturn(Optional.of(document));

        var result = service.findById(document.getId().toHexString());

        assertThat(result.id()).isEqualTo(document.getId().toHexString());
        assertThat(result.windowStartedAt()).isEqualTo(START);
        assertThat(result.lpmAverage()).isEqualTo(2.5);
    }

    @Test
    void findByIdRaisesNotFoundForMissingDocument() {
        var id = new ObjectId();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.findById(id.toHexString()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByDeviceAndPeriodRequiresAnOrderedWindow() {
        assertThatThrownBy(() -> service.findByDeviceAndPeriod(DEVICE_ID, END, START, PageRequest.of(0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anterior");
    }

    @Test
    void findByDeviceAndPeriodRequiresBoundedPagination() {
        assertThatThrownBy(() -> service.findByDeviceAndPeriod(DEVICE_ID, START, END, Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.findByDeviceAndPeriod(DEVICE_ID, START, END, PageRequest.of(0, 101)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
                any(), any(), any(), any());
    }

    @Test
    void findByDeviceAndPeriodUsesRequestedPageAndMapsDocuments() {
        var document = document();
        var expectedPage = PageRequest.of(1, 20);
        when(repository.findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
                eq(DEVICE_ID), eq(START), eq(END), eq(expectedPage)))
                .thenReturn(new PageImpl<>(List.of(document), expectedPage, 21));

        var result = service.findByDeviceAndPeriod(DEVICE_ID, START, END, expectedPage);

        assertThat(result.getTotalElements()).isEqualTo(21);
        assertThat(result.getContent()).singleElement().satisfies(summary -> {
            assertThat(summary.id()).isEqualTo(document.getId().toHexString());
            assertThat(summary.deviceId()).isEqualTo(DEVICE_ID);
        });
    }

    private static ConsumptionSummaryRequest request(Double liters, Double average) {
        return new ConsumptionSummaryRequest(DEVICE_ID, START, END, liters, average);
    }

    private static ConsumptionSummaryDocument document() {
        return new ConsumptionSummaryDocument(new ObjectId(), DEVICE_ID, START, END, 12.5, 2.5);
    }
}
