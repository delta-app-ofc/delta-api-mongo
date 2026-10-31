package br.com.delta.delta_api_mongo.modules.device_status.repository;

import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceStatusIndexesTest {
    @Test
    void createsUniqueDeviceIndexOnlyOnceForTheServiceInstance() {
        var mongo = mock(MongoTemplate.class);
        var indexOperations = mock(IndexOperations.class);
        when(mongo.indexOps(DeviceStatusDocument.class)).thenReturn(indexOperations);
        var indexes = new DeviceStatusIndexes(mongo);

        indexes.ensureCreated();
        indexes.ensureCreated();

        verify(mongo).indexOps(DeviceStatusDocument.class);
        verify(indexOperations).createIndex(any(IndexDefinition.class));
    }
}
