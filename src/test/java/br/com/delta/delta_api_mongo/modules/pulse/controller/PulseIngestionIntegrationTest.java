package br.com.delta.delta_api_mongo.modules.pulse.controller;

import br.com.delta.delta_api_mongo.common.config.DeviceValidationConfig;
import br.com.delta.delta_api_mongo.modules.pulse.document.PulseDocument;
import br.com.delta.delta_api_mongo.modules.pulse.mapper.PulseMapper;
import br.com.delta.delta_api_mongo.modules.pulse.repository.PulseRepository;
import br.com.delta.delta_api_mongo.modules.pulse.service.PulseService;
import com.sun.net.httpserver.HttpServer;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PulseController.class)
@Import({DeviceValidationConfig.class, PulseService.class, PulseMapper.class})
class PulseIngestionIntegrationTest {
    private static final String PATH = "/api/telemetry/pulses";
    private static final String DEVICE = "ESP00321";
    private static final String SERVICE = "delta_svc_" + "s".repeat(43);
    private static final AtomicInteger SQL_CALLS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SQL = startSql();
    private static volatile boolean active;
    private static volatile int sqlStatus;
    private static volatile String sqlBody;
    private static volatile long headerDelay;
    private static volatile long bodyDelay;
    private static volatile boolean closeConnection;
    private static volatile String receivedId;
    private static volatile String receivedAuthorization;
    private static volatile String receivedMethod;
    private static volatile String receivedBody;
    @Autowired private MockMvc mvc;
    @MockitoBean private PulseRepository repository;
    private final List<PulseDocument> stored = new ArrayList<>();

    private static HttpServer startSql() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/delta/internal/devices/validate", exchange -> {
                SQL_CALLS.incrementAndGet();
                receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                receivedMethod = exchange.getRequestMethod();
                receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                receivedId = JsonMapper.builder().build().readTree(receivedBody).path("device_id").stringValue();
                String body = sqlBody != null ? sqlBody : active && DEVICE.equals(receivedId)
                        ? "{\"valid\":true,\"device_id\":\"ESP00321\"}" : "{\"valid\":false}";
                try {
                    if (closeConnection) return;
                    if (headerDelay > 0) Thread.sleep(headerDelay);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(sqlStatus, bytes.length);
                    if (bodyDelay > 0) {
                        exchange.getResponseBody().write(bytes, 0, 1);
                        exchange.getResponseBody().flush();
                        Thread.sleep(bodyDelay);
                        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                    } else exchange.getResponseBody().write(bytes);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally { exchange.close(); }
            });
            server.start();
            return server;
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delta.device-validation.sql-api-base-url", () -> "http://127.0.0.1:" + SQL.getAddress().getPort());
        registry.add("delta.device-validation.service-credential", () -> SERVICE);
        registry.add("delta.device-validation.connect-timeout", () -> "1s");
        registry.add("delta.device-validation.response-timeout", () -> "1s");
    }

    @AfterAll static void stopSql() { SQL.stop(0); EXECUTOR.shutdownNow(); }

    @BeforeEach
    void reset() {
        SQL_CALLS.set(0);
        active = true;
        sqlStatus = 200;
        sqlBody = null;
        headerDelay = bodyDelay = 0;
        closeConnection = false;
        receivedId = receivedAuthorization = receivedMethod = receivedBody = null;
        stored.clear();
        lenient().when(repository.save(any(PulseDocument.class))).thenAnswer(invocation -> {
            PulseDocument document = invocation.getArgument(0);
            document.setId(new ObjectId());
            stored.add(document);
            return document;
        });
    }

    private static String payload(String identityJson) {
        return """
                {"device_id":%s,"sent_at":"2026-09-27T17:13:06Z","window_minutes":5,
                 "total_pulses":1,"pulses":[
                 {"pulsed_at":"2026-09-27T17:12:36Z","ms_since_boot":1000,"delta_ms":0}]}
                """.formatted(identityJson);
    }

    private ResultActions ingest(String deviceId) throws Exception {
        return mvc.perform(post(PATH).contentType("application/json")
                .content(payload(JsonMapper.builder().build().writeValueAsString(deviceId))));
    }

    @Test
    void activeDeviceWritesWithoutHardwareKeyUsingNewSqlContract() throws Exception {
        ingest(DEVICE).andExpect(status().isCreated()).andExpect(jsonPath("$.device_id").value(DEVICE));
        assertThat(stored).singleElement().satisfies(document -> {
            assertThat(document.getDeviceId()).isEqualTo(DEVICE);
            assertThat(document.getPulses()).hasSize(1);
        });
        assertThat(SQL_CALLS).hasValue(1);
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedAuthorization).isEqualTo("Bearer " + SERVICE);
        assertThat(receivedId).isEqualTo(DEVICE);
        assertThat(receivedBody).doesNotContain("api_key", SERVICE);
    }

    @Test
    void deactivationBlocksNextWriteAndReactivationRevalidates() throws Exception {
        ingest(DEVICE).andExpect(status().isCreated());
        active = false;
        ingest(DEVICE).andExpect(status().isForbidden());
        active = true;
        ingest(DEVICE).andExpect(status().isCreated());
        assertThat(SQL_CALLS).hasValue(3);
        assertThat(stored).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER", "ESP321", "esp00321", "ESP00321 "})
    void unknownIdentityIsSentExactlyAndCannotWrite(String identity) throws Exception {
        ingest(identity).andExpect(status().isForbidden());
        assertThat(receivedId).isEqualTo(identity);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"\"", "\"   \"", "123", "true", "[]", "{}"})
    void invalidDeviceIdNeverCallsSqlOrWrites(String identityJson) throws Exception {
        mvc.perform(post(PATH).contentType("application/json").content(payload(identityJson)))
                .andExpect(status().isBadRequest());
        assertThat(SQL_CALLS).hasValue(0);
        verifyNoInteractions(repository);
    }

    @Test
    void missingOrExcessiveDeviceIdNeverCallsSql() throws Exception {
        mvc.perform(post(PATH).contentType("application/json")
                .content(payload("null").replace("\"device_id\":null,", ""))).andExpect(status().isBadRequest());
        ingest("a".repeat(101)).andExpect(status().isBadRequest());
        assertThat(SQL_CALLS).hasValue(0);
        verifyNoInteractions(repository);
    }

    @Test
    void accepts100CharacterId() throws Exception {
        String identity = "a".repeat(100);
        sqlBody = "{\"valid\":true,\"device_id\":\"" + identity + "\"}";
        ingest(identity).andExpect(status().isCreated());
        assertThat(stored.get(0).getDeviceId()).isEqualTo(identity);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void sqlFailureIs503AndNeverWrites(int code) throws Exception {
        sqlStatus = code;
        sqlBody = SERVICE;
        String response = ingest(DEVICE).andExpect(status().isServiceUnavailable())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SERVICE, "127.0.0.1");
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"valid\":true}", "{\"valid\":true,\"device_id\":\"OTHER\"}"})
    void malformedOrMismatchedSqlResponseNeverWrites(String body) throws Exception {
        sqlBody = body;
        ingest(DEVICE).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    void headerTimeoutNeverWrites() throws Exception {
        headerDelay = 1500;
        ingest(DEVICE).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    void bodyTimeoutNeverWrites() throws Exception {
        bodyDelay = 1500;
        ingest(DEVICE).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    void closedConnectionNeverWrites() throws Exception {
        closeConnection = true;
        ingest(DEVICE).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    void invalidBatchNeverCallsSqlOrWrites() throws Exception {
        mvc.perform(post(PATH).contentType("application/json")
                .content(payload("\"ESP00321\"").replace("\"total_pulses\":1", "\"total_pulses\":2")))
                .andExpect(status().isBadRequest());
        assertThat(SQL_CALLS).hasValue(0);
        verifyNoInteractions(repository);
    }

    @Test
    void readsDoNotCallSqlEvenWhenUnavailable() throws Exception {
        sqlStatus = 503;
        ObjectId id = new ObjectId();
        when(repository.findById(id)).thenReturn(Optional.of(new PulseDocument(id, DEVICE,
                java.time.Instant.parse("2026-09-27T17:13:06Z"), 5, 0, List.of())));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk());
        assertThat(SQL_CALLS).hasValue(0);
    }
}
