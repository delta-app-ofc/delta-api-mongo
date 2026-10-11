package br.com.delta.delta_api_mongo.modules.consumption.controller;

import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.mongodb.core.MongoTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in HTTP test with actual SQL and Mongo; removes only this run's exact test windows. */
@EnabledIfSystemProperty(named = "delta.test.live-consumption", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumptionSummaryLiveIntegrationTest {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z")
            .plusMillis(Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 31_536_000_000L));
    private static final Instant MIDDLE = START.plusSeconds(300);
    private static final Instant END = MIDDLE.plusSeconds(300);
    private static final String PATH = "/api/telemetry/consumption-summary";
    private static final String DEVICE = "ESP32-SP-0001";
    @LocalServerPort private int port;
    @Autowired @Qualifier("telemetryMongoTemplate") private MongoTemplate mongo;
    private boolean wroteTestRecords;
    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private Document testWindows() {
        return new Document("device_id", DEVICE).append("$or", List.of(
                new Document("window_started_at", Date.from(START)).append("window_finished_at", Date.from(MIDDLE)),
                new Document("window_started_at", Date.from(MIDDLE)).append("window_finished_at", Date.from(END))));
    }

    @AfterAll
    void cleanupOnlyTestWindows() {
        if (!wroteTestRecords) return;
        mongo.getCollection("consumption_summary").deleteMany(testWindows());
        assertThat(mongo.getCollection("consumption_summary").countDocuments(testWindows())).isZero();
    }

    private URI uri(String suffix) { return URI.create("http://127.0.0.1:" + port + PATH + suffix); }

    private static String payload(String deviceId, String start, String end) {
        return """
                {"device_id":"%s","window_started_at":"%s","window_finished_at":"%s",
                 "consumption_liters":14.4,"lpm_average":2.88}
                """.formatted(deviceId, start, end);
    }

    private HttpRequest post(String body) {
        return HttpRequest.newBuilder(uri("")).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    @Test
    void httpIngestionValidatesRealSqlPersistsAndRejectsConcurrentDuplicates() throws Exception {
        String body = payload(DEVICE, START.toString(), MIDDLE.toString());
        var response = http.send(post(body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        wroteTestRecords = true;
        String id = json.readTree(response.body()).path("_id").stringValue();
        assertThat(response.headers().firstValue("Location")).contains(uri("/" + id).toString());
        var collection = mongo.getCollection("consumption_summary");
        assertThat(collection.countDocuments(testWindows())).isEqualTo(1);
        Document stored = collection.find(testWindows()).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getString("device_id")).isEqualTo(DEVICE);
        assertThat(stored.getDouble("consumption_liters")).isEqualTo(14.4);
        assertThat(stored.getDouble("lpm_average")).isEqualTo(2.88);
        assertThat(stored.keySet()).doesNotContain("api_key", "credential_id", "permissions");

        assertThat(http.send(post(body), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
        assertThat(http.send(post(body.replace(DEVICE, "test_missing_" + UUID.randomUUID())),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(http.send(post(body.replace("14.4", "-1")), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(http.send(post(body.replace("\"" + DEVICE + "\"", "123")),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        assertThat(collection.countDocuments(testWindows())).isEqualTo(1);

        var read = http.send(HttpRequest.newBuilder(uri("/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body()).path("device_id").stringValue()).isEqualTo(DEVICE);
        var page = http.send(HttpRequest.newBuilder(uri("?device_id=" + DEVICE
                + "&start=" + START + "&end=" + MIDDLE)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(json.readTree(page.body()).path("page").path("totalElements").intValue()).isEqualTo(1);
        var exclusive = http.send(HttpRequest.newBuilder(uri("?device_id=" + DEVICE
                + "&start=" + START.minusSeconds(300) + "&end=" + START)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(exclusive.statusCode()).isEqualTo(200);
        assertThat(json.readTree(exclusive.body()).path("page").path("totalElements").intValue()).isZero();

        String concurrentBody = payload(DEVICE, MIDDLE.toString(), END.toString());
        var first = http.sendAsync(post(concurrentBody), HttpResponse.BodyHandlers.ofString());
        var second = http.sendAsync(post(concurrentBody), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(first, second).join();
        assertThat(List.of(first.join().statusCode(), second.join().statusCode())).containsExactlyInAnyOrder(201, 409);
        assertThat(collection.countDocuments(testWindows())).isEqualTo(2);
        System.out.println("HTTP real: SQL ativa, gravação no Mongo, consultas, rejeições e concorrência aprovadas.");
    }
}
