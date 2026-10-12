package br.com.delta.delta_api_mongo.modules.device_status.controller;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in HTTP test using real SQL and MongoDB; refuses to overwrite an existing device status. */
@EnabledIfSystemProperty(named = "delta.test.live-device-status", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeviceStatusLiveIntegrationTest {
    private static final String FIRMWARE = "live-test-" + UUID.randomUUID();
    private static final String DEVICE = System.getProperty("delta.test.live-device-status-device", "ESP32-SP-0001");
    private static final String PATH = "/api/telemetry/device-status";
    private static final Instant START = Instant.parse("2026-07-17T17:10:00Z");
    @LocalServerPort private int port;
    @Autowired @Qualifier("telemetryMongoTemplate") private MongoTemplate mongo;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json = JsonMapper.builder().build();
    private boolean attemptedWrite;

    @AfterAll
    void removeOnlyThisRunsStatus() {
        if (attemptedWrite) {
            var filter = new Document("device_id", DEVICE).append("firmware_version", FIRMWARE);
            var collection = mongo.getCollection("device_status");
            collection.deleteMany(filter);
            assertThat(collection.countDocuments(filter)).isZero();
            System.out.println("HTTP real device_status: registros exclusivos desta execução removidos.");
        }
    }

    private URI uri(String suffix) {
        return URI.create("http://127.0.0.1:" + port + PATH + suffix);
    }

    private String query(String deviceId) {
        return "?device_id=" + URLEncoder.encode(deviceId, StandardCharsets.UTF_8);
    }

    private HttpRequest post(String body) {
        return HttpRequest.newBuilder(uri("")).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private String payload(Instant ping, String connectivity, String reason) {
        return """
                {"device_id":"%s","last_ping_at":"%s","wifi_signal_rssi":"good",
                 "firmware_version":"%s","connectivity_status":"%s","unavailability_reason":%s}
                """.formatted(DEVICE, ping, FIRMWARE, connectivity, reason == null ? "null" : json.writeValueAsString(reason));
    }

    private JsonNode send(HttpRequest request, int expectedStatus) throws Exception {
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return json.readTree(response.body());
    }

    private JsonNode read(String deviceId, int expectedStatus) throws Exception {
        return send(HttpRequest.newBuilder(uri(query(deviceId))).timeout(Duration.ofSeconds(30)).GET().build(), expectedStatus);
    }

    @Test
    void realHardwareRequestsPersistUpdateAndKeepNewestPingUnderConcurrency() throws Exception {
        var collection = mongo.getCollection("device_status");
        var filter = new Document("device_id", DEVICE);
        assertThat(collection.countDocuments(filter))
                .as("O dispositivo de teste já possui status. Use -Ddelta.test.live-device-status-device com um dispositivo dedicado sem status.")
                .isZero();
        read(DEVICE, 404);

        // Exercise simultaneous first inserts, including the unique device index.
        attemptedWrite = true;
        var older = http.sendAsync(post(payload(START, "offline", "wifi_lost")), HttpResponse.BodyHandlers.ofString());
        var newer = http.sendAsync(post(payload(START.plusSeconds(1), "online", null)), HttpResponse.BodyHandlers.ofString());
        CompletableFuture.allOf(older, newer).join();
        assertThat(older.join().statusCode()).as(older.join().body()).isEqualTo(200);
        assertThat(newer.join().statusCode()).as(newer.join().body()).isEqualTo(200);
        var current = read(DEVICE, 200);
        String id = current.path("_id").stringValue();
        assertThat(org.bson.types.ObjectId.isValid(id)).isTrue();
        assertThat(current.path("last_ping_at").stringValue()).isEqualTo(START.plusSeconds(1).toString());
        assertThat(current.path("connectivity_status").stringValue()).isEqualTo("online");
        assertThat(collection.countDocuments(filter)).isEqualTo(1);
        assertThat(collection.listIndexes().into(new ArrayList<>())).anySatisfy(index -> {
            assertThat(index.getString("name")).isEqualTo("idx_device_id_unique");
            assertThat(index.getBoolean("unique")).isTrue();
        });

        var offline = send(post(payload(START.plusSeconds(2), "offline", "wifi_lost")), 200);
        assertThat(offline.path("_id").stringValue()).isEqualTo(id);
        assertThat(offline.path("unavailability_reason").stringValue()).isEqualTo("wifi_lost");
        var recovery = payload(START.plusSeconds(3).plusNanos(123456789), "online", null)
                .replace(",\"unavailability_reason\":null", "");
        var recovered = send(post(recovery), 200);
        assertThat(recovered.path("unavailability_reason").isNull()).isTrue();
        assertThat(recovered.path("last_ping_at").stringValue()).isEqualTo("2026-07-17T17:10:03.123Z");
        var repeated = send(post(recovery), 200);
        assertThat(repeated.path("_id").stringValue()).isEqualTo(id);
        var stale = send(post(payload(START, "offline", "wifi_lost")), 200);
        assertThat(stale).isEqualTo(recovered);

        var pending = new ArrayList<CompletableFuture<HttpResponse<String>>>();
        for (int seconds : List.of(6, 4, 8, 5, 10, 7, 9, 3)) {
            pending.add(http.sendAsync(post(payload(START.plusSeconds(seconds), "online", null)), HttpResponse.BodyHandlers.ofString()));
        }
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
        for (var result : pending) assertThat(result.join().statusCode()).as(result.join().body()).isEqualTo(200);
        var latest = read(DEVICE, 200);
        assertThat(latest.path("_id").stringValue()).isEqualTo(id);
        assertThat(latest.path("last_ping_at").stringValue()).isEqualTo(START.plusSeconds(10).toString());
        var stored = collection.find(filter).first();
        assertThat(stored).isNotNull();
        assertThat(stored.getDate("last_ping_at")).isEqualTo(Date.from(START.plusSeconds(10)));
        assertThat(stored.getString("wifi_signal_rssi")).isEqualTo("good");
        assertThat(stored.getString("firmware_version")).isEqualTo(FIRMWARE);
        assertThat(stored.getString("connectivity_status")).isEqualTo("online");
        assertThat(stored.get("unavailability_reason")).isNull();
        assertThat(collection.countDocuments(filter)).isEqualTo(1);
        System.out.println("HTTP real device_status: SQL ativa, Mongo persistido, criação concorrente, atualização, "
                + "reenvio, ping antigo, recuperação e concorrência de 8 pings aprovados.");
    }

    @Test
    void realRequestsRejectInvalidPayloadsAndUnknownDevicesWithoutWriting() throws Exception {
        var collection = mongo.getCollection("device_status");
        long before = collection.countDocuments();
        String body = payload(START, "online", null);
        for (String invalid : List.of(body.replace("\"" + DEVICE + "\"", "123"),
                body.replace(DEVICE, " "), body.replace(DEVICE, "x".repeat(101)),
                body.replace(START.toString(), "bad-date"), body.replace("good", " "),
                body.replace(FIRMWARE, "x".repeat(101)), body.replace("online", " "),
                body.replace("\"device_id\":\"" + DEVICE + "\",", ""),
                body.replace("\"last_ping_at\":\"" + START + "\"", "\"last_ping_at\":null"),
                body.replace("\"unavailability_reason\":null", "\"unavailability_reason\":\"" + "x".repeat(501) + "\""),
                "{broken")) {
            send(post(invalid), 400);
        }
        String unknown = "test_missing_" + UUID.randomUUID();
        send(post(body.replace(DEVICE, unknown)), 403);
        read(unknown, 404);
        send(HttpRequest.newBuilder(uri("")).GET().build(), 400);
        read(" ", 400);
        read("x".repeat(101), 400);
        assertThat(collection.countDocuments()).isEqualTo(before);
        System.out.println("HTTP real device_status: dados inválidos (400), dispositivo desconhecido (403), "
                + "status ausente (404) e ausência de gravação nas rejeições aprovados.");
    }
}
