package br.com.delta.delta_api_mongo.modules.consumption.repository;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.Instant;

public interface ConsumptionSummaryRepository extends MongoRepository<ConsumptionSummaryDocument, ObjectId> {
    Page<ConsumptionSummaryDocument> findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
            String deviceId, Instant start, Instant end, Pageable pageable);
}
