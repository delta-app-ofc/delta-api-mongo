package br.com.delta.delta_api_mongo.modules.detection.repository;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import java.time.Instant;
import java.util.List;

public interface AlertHistoryRepository extends MongoRepository<AlertHistoryDocument, ObjectId> {
    // Alertas ainda abertos (resolved_at nulo) de um dispositivo e uma regra: base da deduplicação do detector.
    List<AlertHistoryDocument> findByDeviceIdAndRuleAndResolvedAtIsNull(String deviceId, String rule);

    // Alertas abertos com a mesma chave de deduplicação (no máximo um, garantido pelo índice único parcial).
    List<AlertHistoryDocument> findByDedupKeyAndResolvedAtIsNull(String dedupKey);

    // Tela de alertas: do usuário, ainda não dispensados (dismissed_at nulo inclui campo ausente).
    @Query(value = "{'user_id': ?0, 'dismissed_at': null}", sort = "{'triggered_at': -1, '_id': -1}")
    Page<AlertHistoryDocument> findVisibleByUser(Integer userId, Pageable pageable);

    @Query(value = "{'user_id': ?0, 'severity': ?1, 'dismissed_at': null}", sort = "{'triggered_at': -1, '_id': -1}")
    Page<AlertHistoryDocument> findVisibleByUserAndSeverity(Integer userId, String severity, Pageable pageable);

    @Query(value = "{'user_id': ?0}", sort = "{'triggered_at': -1, '_id': -1}")
    Page<AlertHistoryDocument> findAllByUser(Integer userId, Pageable pageable);

    @Query(value = "{'user_id': ?0, 'severity': ?1}", sort = "{'triggered_at': -1, '_id': -1}")
    Page<AlertHistoryDocument> findAllByUserAndSeverity(Integer userId, String severity, Pageable pageable);

    // Vazamentos do usuário: ainda abertos ou abertos desde 'since' (últimas 24 h do cartão).
    @Query(value = "{'user_id': ?0, 'alert_type': 'vazamento_continuo', "
            + "'$or': [{'resolved_at': null}, {'triggered_at': {'$gte': ?1}}]}")
    List<AlertHistoryDocument> findLeakAlerts(Integer userId, Instant since);
}
