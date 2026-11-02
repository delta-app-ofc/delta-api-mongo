package br.com.delta.delta_api_mongo.modules.consumption.document;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Document(collection = "consumption_summary")
@Getter
@Setter
@NoArgsConstructor
public class ConsumptionSummaryDocument {
    @Id
    private ObjectId id;
    @Field("device_id")
    private String deviceId;
    @Field("window_started_at")
    private Instant windowStartedAt;
    @Field("window_finished_at")
    private Instant windowFinishedAt;
    @Field("consumption_liters")
    private Double consumptionLiters;
    @Field("lpm_average")
    private Double lpmAverage;

    // Preenchidos depois da gravação pelo detector de anomalias; nulos até lá.
    @Field("anomaly_detected")
    private Boolean anomalyDetected;
    @Field("anomaly_reasons")
    private List<String> anomalyReasons;
    @Field("anomaly_evidence")
    private Map<String, Object> anomalyEvidence;
    @Field("detection_error")
    private String detectionError;
    @Field("detector_version")
    private String detectorVersion;
    @Field("detection_status")
    private String detectionStatus;

    public ConsumptionSummaryDocument(ObjectId id, String deviceId, Instant windowStartedAt,
                                      Instant windowFinishedAt, Double consumptionLiters, Double lpmAverage) {
        this.id = id;
        this.deviceId = deviceId;
        this.windowStartedAt = windowStartedAt;
        this.windowFinishedAt = windowFinishedAt;
        this.consumptionLiters = consumptionLiters;
        this.lpmAverage = lpmAverage;
    }
}
