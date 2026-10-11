package br.com.delta.delta_api_mongo.modules.consumption.repository;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class ConsumptionSummaryIndexes {
    private final MongoTemplate mongo;
    private boolean created;

    public ConsumptionSummaryIndexes(@Qualifier("telemetryMongoTemplate") MongoTemplate mongo) {
        this.mongo = mongo;
    }

    // Create before the first insert; the database enforces uniqueness across concurrent requests.
    public synchronized void ensureCreated() {
        if (created) return;
        var indexes = mongo.indexOps(ConsumptionSummaryDocument.class);
        indexes.createIndex(new Index().on("device_id", Sort.Direction.ASC)
                .on("window_started_at", Sort.Direction.ASC).on("window_finished_at", Sort.Direction.ASC)
                .unique().named("idx_consumption_summary_device_window_uq"));
        indexes.createIndex(new Index().on("device_id", Sort.Direction.ASC)
                .on("window_started_at", Sort.Direction.DESC).on("_id", Sort.Direction.DESC)
                .named("idx_consumption_summary_device_start"));
        created = true;
    }
}
