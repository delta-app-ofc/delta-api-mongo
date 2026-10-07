package br.com.delta.delta_api_mongo.modules.pulse.controller;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in check against a local Mongo, always using a new isolated database. */
@EnabledIfSystemProperty(named = "delta.test.live-mongo", matches = "true")
@SpringBootTest(properties = {
        "delta.mongodb.telemetry.uri=mongodb://127.0.0.1:27017/?serverSelectionTimeoutMS=1000",
        "delta.device-auth.service-credential=delta_svc_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "delta.device-auth.connect-timeout=1s",
        "delta.device-auth.response-timeout=2s"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PulseMongoIntegrationTest {
    private static final String DATABASE = "delta_device_auth_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PATH = "/api/telemetry/pulses";
    private static final String KEY = "delta_dev_mongo-integration";
    private static final AtomicInteger SQL_CALLS = new AtomicInteger();
    private static volatile int sqlStatus = 200;
    private static volatile String sqlBody = """
            {"valid":true,"device_id":"ESP00321","credential_id":1,"permissions":["telemetry:write"]}
            """;
    private static final HttpServer SQL = startSql();

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("telemetryMongoTemplate")
    private MongoTemplate mongo;

    private static HttpServer startSql() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/delta/internal/device-auth/validate", exchange -> {
                SQL_CALLS.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = sqlBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(sqlStatus, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("SQL de teste indisponível", exception);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delta.mongodb.telemetry.database", () -> DATABASE);
        registry.add("delta.device-auth.sql-api-base-url", () -> "http://127.0.0.1:" + SQL.getAddress().getPort());
    }

    @AfterAll
    void cleanupOnlyThisTestDatabase() {
        SQL.stop(0);
        assertThat(mongo.getDb().getName()).isEqualTo(DATABASE);
        assertThat(DATABASE).startsWith("delta_device_auth_test_");
        mongo.getDb().drop();
    }

    @Test
    void realMongoPersistsOnlyAuthorizedBatchAndPreservesReads() throws Exception {
        String payload = """
                {"device_id":"ESP00321","sent_at":"2026-09-27T17:13:06Z","window_minutes":5,
                 "total_pulses":1,"pulses":[
                   {"pulsed_at":"2026-09-27T17:12:36Z","ms_since_boot":1000,"delta_ms":0}]}
                """;
        var created = mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY)
                        .contentType("application/json").content(payload))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = JsonMapper.builder().build().readTree(created).path("_id").stringValue();
        var collection = mongo.getCollection("pulses_raw");
        assertThat(collection.countDocuments()).isEqualTo(1);
        var raw = collection.find().first();
        assertThat(raw).isNotNull();
        assertThat(raw.getString("device_id")).isEqualTo("ESP00321");
        assertThat(raw.getList("pulses", org.bson.Document.class)).hasSize(1);
        assertThat(raw.toJson()).doesNotContain(KEY, "delta_svc_", "api_key", "credential_id");

        mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY).contentType("application/json")
                        .content(payload.replace("ESP00321", "ESP321")))
                .andExpect(status().isBadRequest());
        sqlBody = "{\"valid\":false}";
        mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY).contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
        sqlStatus = 503;
        mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY).contentType("application/json").content(payload))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post(PATH).contentType("application/json").content(payload))
                .andExpect(status().isUnauthorized());
        assertThat(collection.countDocuments()).isEqualTo(1);
        assertThat(SQL_CALLS).hasValue(4);

        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value("ESP00321"));
        assertThat(SQL_CALLS).hasValue(4);
    }
}
