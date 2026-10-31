package br.com.delta.delta_api_mongo.modules.consumption.repository;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexOperations;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumptionSummaryIndexesTest {
    @Test
    void createsBothIndexesOnlyOnceForTheServiceInstance() {
        var mongo = mock(MongoTemplate.class);
        var indexOperations = mock(IndexOperations.class);
        when(mongo.indexOps(ConsumptionSummaryDocument.class)).thenReturn(indexOperations);
        var indexes = new ConsumptionSummaryIndexes(mongo);

        indexes.ensureCreated();
        indexes.ensureCreated();

        verify(mongo).indexOps(ConsumptionSummaryDocument.class);
        verify(indexOperations, times(2)).createIndex(any(IndexDefinition.class));
    }
}
