package br.com.delta.delta_api_mongo.modules.device_status.repository;

import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Component;

@Component
public class DeviceStatusIndexes {
    private final MongoTemplate mongo;
    private boolean created;

    public DeviceStatusIndexes(@Qualifier("telemetryMongoTemplate") MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public synchronized void ensureCreated() {
        if (created) return;
        mongo.indexOps(DeviceStatusDocument.class).createIndex(new Index().on("device_id", Sort.Direction.ASC)
                .unique().named("idx_device_status_device_uq"));
        created = true;
    }
}
