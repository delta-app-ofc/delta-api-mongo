package br.com.delta.delta_api_mongo.modules.pulse.repository;

import br.com.delta.delta_api_mongo.modules.pulse.document.PulseDocument;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;

public interface PulseRepository extends MongoRepository<PulseDocument, ObjectId> {

    Page<PulseDocument> findByDeviceIdAndSentAtGreaterThanEqualAndSentAtLessThanOrderBySentAtDesc(
            String deviceId,
            Instant start,
            Instant end,
            Pageable pageable
    );
}
