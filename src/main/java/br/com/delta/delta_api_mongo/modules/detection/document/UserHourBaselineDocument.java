package br.com.delta.delta_api_mongo.modules.detection.document;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Baseline por dispositivo e hora da semana (hourOfWeek 0-167).
 * O documento com hourOfWeek = -1 é o "estado do dispositivo": guarda o rastreio de regime e o CUSUM,
 * que valem para o medidor inteiro e não para um slot só.
 */
@Document(collection = "user_hour_baseline")
@Getter
@Setter
@NoArgsConstructor
public class UserHourBaselineDocument {
    public static final int DEVICE_STATE_SLOT = -1;

    @Id
    private ObjectId id;
    @Field("device_id")
    private String deviceId;
    @Field("property_id")
    private Integer propertyId;
    @Field("hour_of_week")
    private int hourOfWeek;
    private Double median;
    private Double mad;
    @Field("ewma_mean")
    private Double ewmaMean;
    @Field("ewma_var")
    private Double ewmaVar;
    private int count;
    @Field("night_floor_lpm")
    private Double nightFloorLpm;
    @Field("min_flow_median")
    private Double minFlowMedian;
    @Field("regime_state")
    private String regimeState = "NORMAL";
    @Field("regime_since")
    private Instant regimeSince;
    @Field("cusum_pos")
    private Double cusumPos = 0.0;
    @Field("cusum_neg")
    private Double cusumNeg = 0.0;
    @Field("last_window_finished_at")
    private Instant lastWindowFinishedAt;
    @Field("config_version")
    private String configVersion;
    @Field("updated_at")
    private Instant updatedAt;
    /** Últimos litros por janela aceitos no slot (base da mediana e do MAD). */
    private List<Double> samples = new ArrayList<>();
    /** Contadores das regras no estado do dispositivo (confirmação, encerramento de alerta, direção do regime). */
    @Field("rule_state")
    private Map<String, Integer> ruleState = new HashMap<>();
    /** Soma da hora em andamento (litros, esperado, variância, janelas): vira um resíduo ao fechar a hora. */
    @Field("regime_acc")
    private Map<String, Double> regimeAcc = new HashMap<>();
    /** Parâmetro opcional e auditável: enquanto vale, uma mudança de regime é tratada como visita declarada. */
    @Field("visit_until")
    private Instant visitUntil;

    public UserHourBaselineDocument(String deviceId, int hourOfWeek) {
        this.deviceId = deviceId;
        this.hourOfWeek = hourOfWeek;
    }

    public int counter(String key) {
        return ruleState.getOrDefault(key, 0);
    }

    public void setCounter(String key, int value) {
        ruleState.put(key, value);
    }
}
