package br.com.delta.delta_api_mongo.modules.consumption.controller;

import br.com.delta.delta_api_mongo.support.TimedHttpClient;
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
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in HTTP test with actual Mongo; removes only this run's exact test windows. */
@EnabledIfSystemProperty(named = "delta.test.live-consumption", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsumptionSummaryLiveIntegrationTest {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z")
            .plusMillis(Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 31_536_000_000L));
    private static final Instant MIDDLE = START.plusSeconds(300);
    private static final Instant END = MIDDLE.plusSeconds(300);
    private static final String PATH = "/api/telemetry/consumption-summary";
    private static final String DEVICE = "test_consumption_" + UUID.randomUUID();
    @LocalServerPort private int port;
    @Autowired @Qualifier("telemetryMongoTemplate") private MongoTemplate mongo;
    private boolean attemptedWrite;
    private final JsonMapper json = JsonMapper.builder().build();
    private final TimedHttpClient http = new TimedHttpClient();

    private Document testWindows() {
        return new Document("device_id", DEVICE).append("$or", List.of(
                new Document("window_started_at", Date.from(START)).append("window_finished_at", Date.from(MIDDLE)),
                new Document("window_started_at", Date.from(MIDDLE)).append("window_finished_at", Date.from(END)),
                new Document("window_started_at", Date.from(END)).append("window_finished_at", Date.from(END.plusSeconds(300)))));
    }

    @AfterAll
    void cleanupOnlyTestWindows() {
        if (!attemptedWrite) return;
        mongo.getCollection("consumption_summary").deleteMany(testWindows());
        assertThat(mongo.getCollection("consumption_summary").countDocuments(testWindows())).isZero();
        System.out.println("HTTP_CLEANUP collection=consumption_summary device_id=" + DEVICE + " remaining=0");
    }

    private URI uri(String suffix) {
        String base = System.getProperty("delta.test.live-consumption-base-url", "http://127.0.0.1:" + port);
        return URI.create(base.replaceAll("/+$", "") + PATH + suffix);
    }

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
    void httpEmptyPeriodQueryUsesRealMongo() throws Exception {
        var emptyPage = http.send(HttpRequest.newBuilder(uri("?device_id=test_missing_" + UUID.randomUUID()
                + "&start=" + START + "&end=" + END + "&size=1")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(emptyPage.statusCode()).as(emptyPage.body()).isEqualTo(200);
        assertThat(json.readTree(emptyPage.body()).path("page").path("totalElements").intValue()).isZero();
        System.out.println("HTTP real: consulta de período vazio retornou 200 em " + uri(""));
    }

    @Test
    void httpIngestionPersistsWithoutDeviceRegistrationAndRejectsConcurrentDuplicates() throws Exception {
        String body = payload(DEVICE, START.toString(), MIDDLE.toString());
        attemptedWrite = true;
        var response = http.send(post(body), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        String id = json.readTree(response.body()).path("_id").stringValue();
        assertThat(response.headers().firstValue("Location")).contains(uri("/" + id).toString());
        var collection = mongo.getCollection("consumption_summary");
        assertThat(collection.countDocuments(testWindows())).isEqualTo(1);

        for (String invalidBody : List.of(
                body.replace("\"" + DEVICE + "\"", "null"),
                body.replace("\"" + DEVICE + "\"", "\" \""),
                body.replace("\"" + DEVICE + "\"", "\"" + "a".repeat(101) + "\""),
                body.replace("\"device_id\":\"" + DEVICE + "\",", ""),
                body.replace("14.4", "null"),
                body.replace("2.88", "-1"),
                body.replace(MIDDLE.toString(), START.toString()),
                body.replace(MIDDLE.toString(), START.minusSeconds(1).toString()),
                body.replace(START.toString(), "not-a-date"),
                "{broken")) {
            assertThat(http.send(post(invalidBody), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        }
        Document stored = collection.find(testWindows()).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getString("device_id")).isEqualTo(DEVICE);
        assertThat(stored.getDouble("consumption_liters")).isEqualTo(14.4);
        assertThat(stored.getDouble("lpm_average")).isEqualTo(2.88);
        assertThat(stored.keySet()).doesNotContain("api_key", "credential_id", "permissions");

        assertThat(http.send(post(body), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(409);
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

        String optionalFlow = payload(DEVICE, END.toString(), END.plusSeconds(300).toString())
                .replace("14.4", "0").replace(",\"lpm_average\":2.88", "");
        assertThat(http.send(post(optionalFlow), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(201);
        assertThat(collection.countDocuments(testWindows())).isEqualTo(3);
        var paginated = http.send(HttpRequest.newBuilder(uri("?device_id=" + DEVICE + "&start=" + START
                + "&end=" + END.plusSeconds(300) + "&size=1&page=0")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(paginated.statusCode()).isEqualTo(200);
        var paginatedJson = json.readTree(paginated.body());
        assertThat(paginatedJson.path("page").path("totalElements").intValue()).isEqualTo(3);
        assertThat(paginatedJson.path("content").size()).isEqualTo(1);
        assertThat(paginatedJson.path("content").get(0).path("consumption_liters").doubleValue()).isZero();

        for (String suffix : List.of("/invalid-id", "?device_id=" + DEVICE,
                "?device_id=" + DEVICE + "&start=" + END + "&end=" + START,
                "?device_id=" + DEVICE + "&start=" + START + "&end=" + END + "&size=101")) {
            assertThat(http.send(HttpRequest.newBuilder(uri(suffix)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(400);
        }
        assertThat(http.send(HttpRequest.newBuilder(uri("/" + new org.bson.types.ObjectId().toHexString())).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(404);
        System.out.println("HTTP real: destino=" + uri("") + "; gravação no Mongo sem cadastro prévio, consultas, paginação, "
                + "consumo zero, vazão opcional, rejeições e concorrência (201/409) aprovadas.");
    }
}
