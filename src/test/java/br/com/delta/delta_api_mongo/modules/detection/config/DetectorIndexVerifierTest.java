package br.com.delta.delta_api_mongo.modules.detection.config;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.bson.Document;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class DetectorIndexVerifierTest {
    @Test
    void requiresRealUniqueBaselineAndAlertLookupIndexes() {
        var mongo = mock(MongoTemplate.class);
        var baselines = mock(IndexOperations.class);
        var alerts = mock(IndexOperations.class);
        when(mongo.indexOps(UserHourBaselineDocument.class)).thenReturn(baselines);
        when(mongo.indexOps(AlertHistoryDocument.class)).thenReturn(alerts);
        var pendingOps = mock(IndexOperations.class);
        when(mongo.indexOps(br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument.class))
                .thenReturn(pendingOps);
        var pending = IndexInfo.indexInfoOf(new Document("name", "pending")
                .append("key", new Document("detection_status", 1).append("window_finished_at", 1).append("_id", 1)));
        when(pendingOps.getIndexInfo()).thenReturn(List.of(pending));
        var baseline = IndexInfo.indexInfoOf(new Document("name", "baseline")
                .append("key", new Document("device_id", 1).append("hour_of_week", 1)).append("unique", true));
        var lookup = IndexInfo.indexInfoOf(new Document("name", "alerts")
                .append("key", new Document("device_id", 1).append("rule", 1).append("resolved_at", 1)));
        when(baselines.getIndexInfo()).thenReturn(List.of());
        when(alerts.getIndexInfo()).thenReturn(List.of(lookup));
        var verifier = new DetectorIndexVerifier(mongo, mongo);
        assertThatThrownBy(() -> verifier.run(null)).isInstanceOf(IllegalStateException.class);
        when(baselines.getIndexInfo()).thenReturn(List.of(baseline));
        assertThatCode(() -> verifier.run(null)).doesNotThrowAnyException();
        when(alerts.getIndexInfo()).thenReturn(List.of());
        assertThatThrownBy(() -> verifier.run(null)).isInstanceOf(IllegalStateException.class);
        when(alerts.getIndexInfo()).thenReturn(List.of(lookup));
        when(pendingOps.getIndexInfo()).thenReturn(List.of());
        assertThatThrownBy(() -> verifier.run(null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void doesNotBlockStartupWhenTheServiceAccountCannotListIndexes() {
        var mongo = mock(MongoTemplate.class);
        var baselines = mock(IndexOperations.class);
        when(mongo.indexOps(UserHourBaselineDocument.class)).thenReturn(baselines);
        when(baselines.getIndexInfo()).thenThrow(new org.springframework.data.mongodb.UncategorizedMongoDbException(
                "not authorized to execute command listIndexes", new RuntimeException("unauthorized")));

        assertThatCode(() -> new DetectorIndexVerifier(mongo, mongo).run(null)).doesNotThrowAnyException();
    }

    @Test
    void anAlertLookupIndexThatIsPartialDoesNotCount() {
        var mongo = mock(MongoTemplate.class);
        var baselines = mock(IndexOperations.class);
        var alerts = mock(IndexOperations.class);
        when(mongo.indexOps(UserHourBaselineDocument.class)).thenReturn(baselines);
        when(mongo.indexOps(AlertHistoryDocument.class)).thenReturn(alerts);
        when(baselines.getIndexInfo()).thenReturn(List.of(IndexInfo.indexInfoOf(new Document("name", "baseline")
                .append("key", new Document("device_id", 1).append("hour_of_week", 1)).append("unique", true))));
        when(alerts.getIndexInfo()).thenReturn(List.of(IndexInfo.indexInfoOf(new Document("name", "partial")
                .append("key", new Document("device_id", 1).append("rule", 1).append("resolved_at", 1))
                .append("partialFilterExpression", new Document("resolved_at", (Object) null)))));

        assertThatThrownBy(() -> new DetectorIndexVerifier(mongo, mongo).run(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("script-indexes.js");
    }
}
