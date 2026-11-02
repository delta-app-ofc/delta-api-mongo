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
    @Field("property_id")
    private Integer propertyId;
    @Field("user_id")
    private Integer userId;
    @Field("alert_type")
    private String alertType;
    @Field("triggered_at")
    private Instant triggeredAt;
    @Field("resolved_at")
    private Instant resolvedAt;
    @Field("dismissed_at")
    private Instant dismissedAt;
    private String severity;
    private String title;
    private String message;
    /** Quem gerou o alerta: detector, gluh (IA) ou system. */
    private String source;
    /** Chave de deduplicação: enquanto existe um alerta aberto com a mesma chave, não se cria outro. */
    @Field("dedup_key")
    private String dedupKey;
    private String rule;
    private Map<String, Object> evidence;
}
