package br.com.delta.delta_api_mongo.modules.detection.service;

import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import org.springframework.data.domain.Sort;
import java.util.List;

/** Grava o resultado do detector na própria janela em consumption_summary (banco de telemetria). */
@Component
public class DetectionResultWriter {
    private final MongoTemplate telemetry;

    public DetectionResultWriter(@Qualifier("telemetryMongoTemplate") MongoTemplate telemetry) {
        this.telemetry = telemetry;
    }

    public void write(ObjectId id, DetectionResult result, String detectorVersion) {
        var update = new Update()
                .set("anomaly_detected", result.anomalyDetected())
                .set("anomaly_reasons", result.reasons())
                .set("anomaly_evidence", result.evidence())
                .set("detector_version", detectorVersion)
                .set("detection_status", "completed");
        if (result.error() != null) update.set("detection_error", result.error());
        else update.unset("detection_error");
        telemetry.updateFirst(Query.query(Criteria.where("_id").is(id)), update, ConsumptionSummaryDocument.class);
    }

    public List<ConsumptionSummaryDocument> pending(int limit) {
        var query = Query.query(Criteria.where("detection_status").is("pending"))
                .with(Sort.by(Sort.Direction.ASC, "window_finished_at", "_id")).limit(limit);
        return telemetry.find(query, ConsumptionSummaryDocument.class);
    }
}
