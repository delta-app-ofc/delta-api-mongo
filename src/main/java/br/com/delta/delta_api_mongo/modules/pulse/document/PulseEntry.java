package br.com.delta.delta_api_mongo.modules.pulse.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PulseEntry {

    @Field("pulsed_at")
    private Instant pulsedAt;

    @Field("ms_since_boot")
    private Long msSinceBoot;

    @Field("delta_ms")
    private Long deltaMs;
}
