package br.com.delta.delta_api_mongo.modules.detection.config;

import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import com.mongodb.MongoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.stereotype.Component;
import java.util.List;

/** Confere os índices provisionados pelo administrador; a conta da API não precisa de createIndex. */
@Component
@ConditionalOnProperty(prefix = "delta.detection", name = "enabled", havingValue = "true")
public class DetectorIndexVerifier implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DetectorIndexVerifier.class);

    private final MongoTemplate mongo;
    private final MongoTemplate telemetry;

    public DetectorIndexVerifier(@Qualifier("appMongoTemplate") MongoTemplate mongo,
                                 @Qualifier("telemetryMongoTemplate") MongoTemplate telemetry) {
        this.mongo = mongo;
        this.telemetry = telemetry;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        List<IndexInfo> baselineIndexes;
        List<IndexInfo> alertIndexes;
        try {
            baselineIndexes = mongo.indexOps(UserHourBaselineDocument.class).getIndexInfo();
            alertIndexes = mongo.indexOps(AlertHistoryDocument.class).getIndexInfo();
        } catch (DataAccessException | MongoException exception) {
            // A conta de serviço pode não ter permissão para listar índices: não dá para conferir, mas isso não
            // deve impedir a API de subir. O administrador continua responsável por aplicar o script.
            log.warn("Não foi possível conferir os índices do detector ({}). Confirme que "
                    + "scripts/delta-app/script-indexes.js foi aplicado.", exception.getClass().getSimpleName());
            return;
        }
        boolean baseline = baselineIndexes.stream()
                .anyMatch(index -> index.isUnique() && !index.isSparse()
                        && index.getPartialFilterExpression() == null
                        && keys(index).equals(List.of("device_id", "hour_of_week")));
        boolean alerts = alertIndexes.stream()
                .anyMatch(index -> index.getPartialFilterExpression() == null
                        && keys(index).equals(List.of("device_id", "rule", "resolved_at")));
        boolean pending = baseline && alerts && telemetry.indexOps(br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument.class)
                .getIndexInfo().stream().anyMatch(index -> keys(index)
                        .equals(List.of("detection_status", "window_finished_at", "_id")));
        if (!baseline || !alerts || !pending) {
            throw new IllegalStateException("Aplique scripts/delta-app/script-indexes.js e scripts/delta-telemetry/script-indexes.js do delta-mongo-database "
                    + "antes de habilitar o detector: índices obrigatórios de baseline/alertas ausentes.");
        }
    }

    private static List<String> keys(IndexInfo index) {
        return index.getIndexFields().stream().map(field -> field.getKey()).toList();
    }
}
