package br.com.delta.delta_api_mongo.modules.consumption.repository;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.Instant;

public interface ConsumptionSummaryRepository extends MongoRepository<ConsumptionSummaryDocument, ObjectId> {
    @Query(value = "{'device_id': ?0, 'window_started_at': {'$gte': ?1, '$lt': ?2}}",
            sort = "{'window_started_at': -1, '_id': -1}")
    Page<ConsumptionSummaryDocument> findByDeviceIdAndWindowStartedAtGreaterThanEqualAndWindowStartedAtLessThanOrderByWindowStartedAtDescIdDesc(
            String deviceId, Instant start, Instant end, Pageable pageable);
}
