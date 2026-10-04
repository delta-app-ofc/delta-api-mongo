package br.com.delta.delta_api_mongo.modules.pulse.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "pulses_raw")
public class PulseDocument {

    @Id
    private ObjectId id;

    @Field("device_id")
    private String deviceId;

    @Field("sent_at")
    private Instant sentAt;

    @Field("window_minutes")
    private Integer windowMinutes;

    @Field("total_pulses")
    private Integer totalPulses;

    @Field("pulses")
    private List<PulseEntry> pulses;
}
