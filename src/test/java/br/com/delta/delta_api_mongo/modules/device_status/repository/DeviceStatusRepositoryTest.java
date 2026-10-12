package br.com.delta.delta_api_mongo.modules.device_status.repository;

import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import java.time.Instant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeviceStatusRepositoryTest {
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final DeviceStatusRepository repository = new DeviceStatusRepository(mongo);
    private static final Instant PING = Instant.parse("2026-07-17T17:10:00Z");

    private DeviceStatusDocument document(Instant ping) {
        return new DeviceStatusDocument(new ObjectId(), "ESP32-SP-0912", ping, "good", "v1.2.3", "online", null);
    }

    @Test
    void atomicUpdateFiltersTimestampAndClearsPreviousReason() {
        var document = document(PING);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeviceStatusDocument.class)))
                .thenReturn(document);
        assertThat(repository.saveLatest(document)).isSameAs(document);
        var query = ArgumentCaptor.forClass(Query.class);
        var update = ArgumentCaptor.forClass(Update.class);
        var options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo).findAndModify(query.capture(), update.capture(), options.capture(), eq(DeviceStatusDocument.class));
        assertThat(query.getValue().getQueryObject().get("device_id")).isEqualTo(document.getDeviceId());
        assertThat(query.getValue().getQueryObject().get("last_ping_at", org.bson.Document.class).get("$lte")).isEqualTo(PING);
        assertThat(update.getValue().getUpdateObject().get("$set", org.bson.Document.class))
                .containsEntry("unavailability_reason", null);
        assertThat(options.getValue().isUpsert()).isTrue();
        assertThat(options.getValue().isReturnNew()).isTrue();
    }

    @Test
    void stalePingReturnsNewerStateWithoutUpdatingIt() {
        var current = document(PING.plusSeconds(60));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeviceStatusDocument.class)))
                .thenThrow(new DuplicateKeyException("device_id"));
        when(mongo.findOne(any(Query.class), eq(DeviceStatusDocument.class))).thenReturn(current);
        assertThat(repository.saveLatest(document(PING))).isSameAs(current);
        verify(mongo, times(1)).findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeviceStatusDocument.class));
    }

    @Test
    void concurrentOlderInsertIsUpdatedWithoutAnotherUpsert() {
        var newer = document(PING);
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeviceStatusDocument.class)))
                .thenThrow(new DuplicateKeyException("device_id")).thenReturn(newer);
        when(mongo.findOne(any(Query.class), eq(DeviceStatusDocument.class))).thenReturn(document(PING.minusSeconds(60)));
        assertThat(repository.saveLatest(newer)).isSameAs(newer);
        var options = ArgumentCaptor.forClass(FindAndModifyOptions.class);
        verify(mongo, times(2)).findAndModify(any(Query.class), any(Update.class), options.capture(), eq(DeviceStatusDocument.class));
        assertThat(options.getAllValues().get(1).isUpsert()).isFalse();
    }

    @Test
    void newerConcurrentUpdateWinsWhileRetrying() {
        var current = document(PING.plusSeconds(60));
        when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(DeviceStatusDocument.class)))
                .thenThrow(new DuplicateKeyException("device_id")).thenReturn(null);
        when(mongo.findOne(any(Query.class), eq(DeviceStatusDocument.class)))
                .thenReturn(document(PING.minusSeconds(60))).thenReturn(current);
        assertThat(repository.saveLatest(document(PING))).isSameAs(current);
    }
}
