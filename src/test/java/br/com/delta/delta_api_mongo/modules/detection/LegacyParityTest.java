package br.com.delta.delta_api_mongo.modules.detection;
import br.com.delta.delta_api_mongo.modules.detection.model.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class LegacyParityTest {
    @Test void verifiesExportedLegacyCasesAndDocumentsIntentionalChanges() throws Exception {
        try (var input=getClass().getResourceAsStream("/detection/legacy-parity.json")) {
            var root=JsonMapper.builder().build().readTree(input);
            assertThat(root.path("source_commit").asText()).hasSize(40);
            for (var scenario:root.path("cases")) {
                var harness=new DetectorHarness(DetectionTestSupport.properties(), Optional.of(
                    new DeviceOwner(1,scenario.path("profile").asText(),List.of(7),null)));
                br.com.delta.delta_api_mongo.modules.detection.model.DetectionResult last=null;
                for (var w:scenario.path("windows")) {
                    double liters=w.path("liters").asDouble();
                    last=harness.feed("PARITY",Instant.parse(w.path("start").asText()),liters,liters/5);
                }
                assertThat(last.anomalyDetected()).as(scenario.path("name").asText())
                    .isEqualTo(scenario.path("java_anomaly").asBoolean());
                if (scenario.path("legacy_anomaly").asBoolean()!=scenario.path("java_anomaly").asBoolean())
                    assertThat(scenario.path("intentional_change").asText()).isNotBlank();
            }
        }
    }
}
