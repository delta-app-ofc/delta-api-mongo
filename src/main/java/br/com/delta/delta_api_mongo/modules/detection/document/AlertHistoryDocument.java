package br.com.delta.delta_api_mongo.modules.detection.document;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;
import java.util.Map;

/** Alerta para o usuário (tela de notificações). Um documento por usuário do imóvel. */
@Document(collection = "alerts_history")
@Getter
@Setter
@NoArgsConstructor
public class AlertHistoryDocument {
    @Id
    private ObjectId id;
    @Field("device_id")
    private String deviceId;
    @Field("user_id")
    private Integer userId;
    @Field("alert_type")
    private String alertType;
    @Field("triggered_at")
    private Instant triggeredAt;
    @Field("resolved_at")
    private Instant resolvedAt;
    private String severity;
    private String rule;
    private Map<String, Object> evidence;
}
