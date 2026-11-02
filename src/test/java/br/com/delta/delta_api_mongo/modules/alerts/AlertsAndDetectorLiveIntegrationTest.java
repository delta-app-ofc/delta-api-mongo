package br.com.delta.delta_api_mongo.modules.alerts;

import br.com.delta.delta_api_mongo.modules.alerts.dto.request.CreateAlertRequest;
import br.com.delta.delta_api_mongo.modules.alerts.service.AlertsService;
import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import br.com.delta.delta_api_mongo.modules.detection.document.AlertHistoryDocument;
import br.com.delta.delta_api_mongo.modules.detection.document.UserHourBaselineDocument;
import br.com.delta.delta_api_mongo.modules.detection.repository.AlertHistoryRepository;
import br.com.delta.delta_api_mongo.modules.detection.repository.UserHourBaselineRepository;
import br.com.delta.delta_api_mongo.modules.detection.service.AnomalyDetectionService;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Teste opt-in contra um MongoDB real (consultas, índices únicos e o detector de ponta a ponta).
 * Pré-requisito: bancos criados com os scripts do delta-mongo-database (coleções, índices e a migração de
 * alerts_history). Rodar com:
 * ./mvnw test -Dtest=AlertsAndDetectorLiveIntegrationTest -Ddelta.test.live-alerts=true
 *   -Ddelta.test.live-mongo-uri=mongodb://127.0.0.1:27019
 * Usa ids únicos desta execução e remove só o que criou.
 */
@EnabledIfSystemProperty(named = "delta.test.live-alerts", matches = "true")
@SpringBootTest(properties = {
        "delta.mongodb.telemetry.uri=${delta.test.live-mongo-uri:mongodb://127.0.0.1:27019}",
        "delta.mongodb.telemetry.database=db_delta_telemetry",
        "delta.mongodb.app.uri=${delta.test.live-mongo-uri:mongodb://127.0.0.1:27019}",
        "delta.mongodb.app.database=db_delta_app",
        "delta.detection.enabled=true", "delta.alerts.enabled=true", "delta.alerts.service-key=live-test-key"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertsAndDetectorLiveIntegrationTest {
    private static final int USER = 900_000 + Math.floorMod(UUID.randomUUID().hashCode(), 90_000);
    private static final int OTHER_USER = USER + 1;
    private static final int UNIQUE_USER = USER + 2;
    private static final int LEAK_USER = USER + 3;
    private static final String DEVICE = "live_test_" + UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2030-03-04T15:00:00Z");

    @Autowired private AlertsService alerts;
    @Autowired private AlertHistoryRepository alertRepository;
    @Autowired private UserHourBaselineRepository baselineRepository;
    @Autowired private ConsumptionSummaryRepository consumptionRepository;
    @Autowired private AnomalyDetectionService detection;
    @Autowired @Qualifier("appMongoTemplate") private MongoTemplate app;
    @Autowired @Qualifier("telemetryMongoTemplate") private MongoTemplate telemetry;

    @AfterAll
    void cleanup() {
        app.remove(new Query(Criteria.where("user_id").in(USER, OTHER_USER, UNIQUE_USER, LEAK_USER)), "alerts_history");
        app.remove(new Query(Criteria.where("device_id").is(DEVICE)), "alerts_history");
        app.remove(new Query(Criteria.where("device_id").is(DEVICE)), "user_hour_baseline");
        telemetry.remove(new Query(Criteria.where("device_id").is(DEVICE)), "consumption_summary");
    }

    private CreateAlertRequest request(String type, String severity, String key, Integer user) {
        return new CreateAlertRequest(user, type, severity, "Título " + type, "Mensagem", "gluh", key, 1, null,
                Map.of("projected_percent", 108), null);
    }

    @Test
    void createListDismissAndResolveAgainstARealMongo() {
        String key = "gluh:" + USER + ":previsao_ultrapassagem_meta:2030-03";
        var created = alerts.create(request("previsao_ultrapassagem_meta", "medium", key, USER));
        assertThat(created.created()).isTrue();

        // a segunda chamada com a mesma chave devolve o mesmo alerta, sem duplicar
        var again = alerts.create(request("previsao_ultrapassagem_meta", "medium", key, USER));
        assertThat(again.created()).isFalse();
        assertThat(again.alert().id()).isEqualTo(created.alert().id());
        assertThat(alertRepository.findByDedupKeyAndResolvedAtIsNull(key)).hasSize(1);

        alerts.create(request("limite_ultrapassado", "high", "gluh:" + USER + ":limite_ultrapassado:2030-03", USER));
        alerts.create(request("dica_personalizada", "low", "gluh:" + USER + ":dica_personalizada:2030-03-04", USER));
        alerts.create(request("dica_personalizada", "low", "gluh:" + OTHER_USER + ":dica_personalizada:2030-03-04",
                OTHER_USER));

        // listagem: só do usuário, mais novo primeiro, filtro de severidade e dispensados
        var all = alerts.list(USER, null, false, PageRequest.of(0, 20));
        assertThat(all.getContent()).hasSize(3);
        assertThat(all.getContent()).extracting(a -> a.alertType()).doesNotContain("dica_personalizada_de_outro");
        assertThat(alerts.list(USER, "high", false, PageRequest.of(0, 20)).getContent()).hasSize(1);
        assertThat(alerts.list(USER, "low", false, PageRequest.of(0, 20)).getContent()).hasSize(1);
        assertThat(all.getContent().get(0).triggeredAt()).isAfterOrEqualTo(all.getContent().get(2).triggeredAt());

        // dispensar: some da lista padrão, aparece com include_dismissed, e outro usuário não consegue dispensar
        var dismissed = alerts.dismiss(USER, created.alert().id());
        assertThat(dismissed.dismissedAt()).isNotNull();
        assertThat(alerts.list(USER, null, false, PageRequest.of(0, 20)).getContent()).hasSize(2);
        assertThat(alerts.list(USER, null, true, PageRequest.of(0, 20)).getContent()).hasSize(3);
        assertThatThrownBy(() -> alerts.dismiss(OTHER_USER, created.alert().id())).hasMessageContaining("não encontrado");

        // encerrar e reabrir com a mesma chave
        assertThat(alerts.resolve(key).resolved()).isEqualTo(1);
        assertThat(alerts.resolve(key).resolved()).isZero();
        assertThat(alerts.create(request("previsao_ultrapassagem_meta", "medium", key, USER)).created()).isTrue();
    }

    @Test
    void theUniqueIndexRejectsASecondOpenAlertWithTheSameKeyEvenBypassingTheService() {
        String key = "gluh:" + UNIQUE_USER + ":dica_personalizada:2030-03-05";
        var first = new AlertHistoryDocument();
        first.setUserId(UNIQUE_USER);
        first.setAlertType("dica_personalizada");
        first.setTriggeredAt(T0);
        first.setSource("gluh");
        first.setDedupKey(key);
        alertRepository.insert(first);

        var second = new AlertHistoryDocument();
        second.setUserId(UNIQUE_USER);
        second.setAlertType("dica_personalizada");
        second.setTriggeredAt(T0);
        second.setSource("gluh");
        second.setDedupKey(key);
        assertThatThrownBy(() -> alertRepository.insert(second)).isInstanceOf(DuplicateKeyException.class);

        // depois de resolvido, a chave pode abrir outro
        first.setResolvedAt(T0.plusSeconds(60));
        alertRepository.save(first);
        second.setId(null);
        assertThat(alertRepository.insert(second).getId()).isNotNull();
    }

    @Test
    void leakSummaryAndOwnerlessLeakAlertsAreReadFromTheRealCollection() {
        var leak = new AlertHistoryDocument();
        leak.setUserId(LEAK_USER);
        leak.setDeviceId(DEVICE);
        leak.setAlertType("vazamento_continuo");
        leak.setSeverity("high");
        leak.setRule("continuous_flow");
        leak.setTriggeredAt(Instant.now().minusSeconds(3600));
        leak.setSource("detector");
        leak.setDedupKey("detector:" + DEVICE + ":continuous_flow:" + LEAK_USER);
        leak.setEvidence(Map.of("liters_per_day_estimate", 1500));
        alertRepository.insert(leak);

        var summary = alerts.leakSummary(LEAK_USER);

        assertThat(summary.litersPerDay()).isEqualTo(1500);
        assertThat(summary.points()).isEqualTo(1);
        assertThat(alertRepository.findByDeviceIdAndRuleAndResolvedAtIsNull(DEVICE, "continuous_flow")).hasSize(1);
    }

    @Test
    void theBaselineCollectionHasOneDocumentPerDeviceAndSlot() {
        var slot = new UserHourBaselineDocument(DEVICE, 12);
        slot.setCount(1);
        baselineRepository.insert(slot);
        var duplicate = new UserHourBaselineDocument(DEVICE, 12);
        duplicate.setCount(1);

        assertThatThrownBy(() -> baselineRepository.insert(duplicate)).isInstanceOf(DuplicateKeyException.class);
        assertThat(baselineRepository.findByDeviceIdAndHourOfWeek(DEVICE, 12)).isPresent();
    }

    @Test
    void theDetectorMarksASteadyFlowWindowAndKeepsItsStateInTheRealDatabase() {
        String device = DEVICE + "_flow";
        ConsumptionSummaryDocument last = null;
        for (int i = 0; i < 7; i++) {
            Instant start = T0.plusSeconds(300L * i);
            last = consumptionRepository.insert(new ConsumptionSummaryDocument(null, device, start,
                    start.plusSeconds(300), 1.0, 0.2));
            detection.processNow(last);
        }

        var stored = consumptionRepository.findById(last.getId()).orElseThrow();
        assertThat(stored.getAnomalyDetected()).isTrue();
        assertThat(stored.getAnomalyReasons()).contains("continuous_flow");
        assertThat(stored.getDetectorVersion()).startsWith("1/");
        var state = baselineRepository.findByDeviceIdAndHourOfWeek(device, UserHourBaselineDocument.DEVICE_STATE_SLOT);
        assertThat(state).isPresent();
        assertThat(state.get().getLastWindowFinishedAt()).isEqualTo(last.getWindowFinishedAt());

        // a mesma janela de novo não é reavaliada nem altera o baseline
        detection.processNow(last);
        var again = consumptionRepository.findById(last.getId()).orElseThrow();
        assertThat(again.getAnomalyDetected()).isNull();
        assertThat(again.getDetectionError()).contains("anterior ou igual");
        assertThat(baselineRepository.findByDeviceIdAndHourOfWeek(device, UserHourBaselineDocument.DEVICE_STATE_SLOT)
                .orElseThrow().getLastWindowFinishedAt()).isEqualTo(last.getWindowFinishedAt());

        telemetry.remove(new Query(Criteria.where("device_id").is(device)), "consumption_summary");
        app.remove(new Query(Criteria.where("device_id").is(device)), "user_hour_baseline");
        assertThat(new ObjectId(last.getId().toHexString())).isNotNull();
    }
}
