package br.com.delta.delta_api_mongo.modules.detection.repository;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.List;

public interface AlertHistoryRepository extends MongoRepository<AlertHistoryDocument, ObjectId> {
    // Alertas ainda abertos (resolved_at nulo) de um dispositivo e uma regra: base da deduplicação.
    List<AlertHistoryDocument> findByDeviceIdAndRuleAndResolvedAtIsNull(String deviceId, String rule);
}
