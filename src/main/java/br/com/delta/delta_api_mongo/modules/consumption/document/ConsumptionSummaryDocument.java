package br.com.delta.delta_api_mongo.modules.consumption.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;

@Document(collection = "consumption_summary")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
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
}
