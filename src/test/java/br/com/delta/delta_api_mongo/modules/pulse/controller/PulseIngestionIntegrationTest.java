package br.com.delta.delta_api_mongo.modules.pulse.controller;

import br.com.delta.delta_api_mongo.common.config.DeviceAuthConfig;
import br.com.delta.delta_api_mongo.common.config.DeviceAuthWebConfig;
import br.com.delta.delta_api_mongo.common.deviceauth.DeviceAuthInterceptor;
import br.com.delta.delta_api_mongo.modules.pulse.document.PulseDocument;
import br.com.delta.delta_api_mongo.modules.pulse.mapper.PulseMapper;
import br.com.delta.delta_api_mongo.modules.pulse.repository.PulseRepository;
import br.com.delta.delta_api_mongo.modules.pulse.service.PulseService;
import com.sun.net.httpserver.HttpServer;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.convert.NoOpDbRefResolver;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;
import org.springframework.http.MediaType;
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
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PulseController.class)
@Import({DeviceAuthConfig.class, DeviceAuthWebConfig.class, DeviceAuthInterceptor.class,
        PulseService.class, PulseMapper.class})
@ExtendWith(OutputCaptureExtension.class)
class PulseIngestionIntegrationTest {
    private static final String PATH = "/api/telemetry/pulses";
    private static final String KEY = "delta_dev_old-test-key";
    private static final String NEW_KEY = "delta_dev_new-test-key";
    private static final String SERVICE = "delta_svc_" + "s".repeat(43);
    private static final String DEVICE = "ESP00321";
    private static final AtomicInteger SQL_CALLS = new AtomicInteger();
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();
    private static final HttpServer SQL = startSql();
    private static volatile int sqlStatus = 200;
    private static volatile String sqlBody;
    private static volatile long sqlDelay;
    private static volatile long sqlBodyDelay;
    private static volatile boolean closeConnection;
    private static volatile boolean active;
    private static volatile Set<String> validKeys = Set.of();
    private static volatile String receivedAuthorization;
    private static volatile String receivedApiKey;
    private static volatile String receivedMethod;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PulseRepository repository;

    private final List<PulseDocument> stored = new ArrayList<>();

    private static HttpServer startSql() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(EXECUTOR);
            server.createContext("/delta/internal/device-auth/validate", exchange -> {
                SQL_CALLS.incrementAndGet();
                receivedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                receivedMethod = exchange.getRequestMethod();
                var request = JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes());
                receivedApiKey = request.path("api_key").stringValue();
                int status = sqlStatus;
                long delay = sqlDelay;
                String body = sqlBody != null ? sqlBody
                        : active && validKeys.contains(receivedApiKey) ? authorizedBody() : "{\"valid\":false}";
                try {
                    if (closeConnection) return;
                    if (delay > 0) Thread.sleep(delay);
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.getResponseHeaders().add("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(status, bytes.length);
                    if (sqlBodyDelay > 0) {
                        exchange.getResponseBody().write(bytes, 0, 1);
                        exchange.getResponseBody().flush();
                        Thread.sleep(sqlBodyDelay);
                        exchange.getResponseBody().write(bytes, 1, bytes.length - 1);
                    } else {
                        exchange.getResponseBody().write(bytes);
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return server;
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível iniciar a SQL simulada.", exception);
        }
    }

    private static String authorizedBody() {
        return "{\"valid\":true,\"device_id\":\"" + DEVICE
                + "\",\"credential_id\":1,\"permissions\":[\"telemetry:write\"]}";
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("delta.device-auth.sql-api-base-url", () -> "http://127.0.0.1:" + SQL.getAddress().getPort());
        registry.add("delta.device-auth.service-credential", () -> SERVICE);
        registry.add("delta.device-auth.connect-timeout", () -> "1s");
        registry.add("delta.device-auth.response-timeout", () -> "1s");
    }

    @AfterAll
    static void stopSql() {
        SQL.stop(0);
        EXECUTOR.shutdownNow();
    }

    @BeforeEach
    void reset() {
        SQL_CALLS.set(0);
        sqlStatus = 200;
        sqlBody = null;
        sqlDelay = 0;
        sqlBodyDelay = 0;
        closeConnection = false;
        active = true;
        validKeys = Set.of(KEY);
        receivedAuthorization = null;
        receivedApiKey = null;
        receivedMethod = null;
        stored.clear();
        lenient().when(repository.save(any(PulseDocument.class))).thenAnswer(invocation -> {
            PulseDocument document = invocation.getArgument(0);
            document.setId(new ObjectId());
            stored.add(document);
            return document;
        });
    }

    private static String payload(String identity) {
        return """
                {"device_id":"%s","sent_at":"2026-09-27T17:13:06Z","window_minutes":5,
                 "total_pulses":2,"pulses":[
                   {"pulsed_at":"2026-09-27T17:12:36Z","ms_since_boot":1000,"delta_ms":0},
                   {"pulsed_at":"2026-09-27T17:12:46Z","ms_since_boot":2000,"delta_ms":1000}]}
                """.formatted(identity);
    }

    private ResultActions ingest(String key, String identity) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + key)
                .contentType(MediaType.APPLICATION_JSON).content(payload(identity)));
    }

    @Test
    void persistsCanonicalStringWithAllPulsesAndNoSecrets(CapturedOutput output) throws Exception {
        ingest(KEY, DEVICE).andExpect(status().isCreated())
                .andExpect(jsonPath("$.device_id").value(DEVICE))
                .andExpect(jsonPath("$.pulses.length()").value(2));
        assertThat(stored).singleElement().satisfies(document -> {
            assertThat(document.getDeviceId()).isEqualTo(DEVICE);
            assertThat(document.getPulses()).hasSize(2);
        });
        assertThat(receivedMethod).isEqualTo("POST");
        assertThat(receivedAuthorization).isEqualTo("Bearer " + SERVICE);
        assertThat(receivedApiKey).isEqualTo(KEY);

        // Verify the actual BSON mapping as well as the response and captured application logs.
        var context = new MongoMappingContext();
        var conversions = MongoCustomConversions.create(adapter -> {});
        context.setSimpleTypeHolder(conversions.getSimpleTypeHolder());
        context.setInitialEntitySet(Set.of(PulseDocument.class));
        context.afterPropertiesSet();
        var converter = new MappingMongoConverter(NoOpDbRefResolver.INSTANCE, context);
        converter.setCustomConversions(conversions);
        converter.afterPropertiesSet();
        var bson = new Document();
        converter.write(stored.get(0), bson);
        assertThat(bson.getString("device_id")).isEqualTo(DEVICE);
        assertThat(bson.keySet()).doesNotContain("api_key", "credential_id", "service_credential", "permissions");
        assertThat(bson.toJson()).doesNotContain(KEY, SERVICE);
        assertThat(output.getAll()).doesNotContain(KEY, SERVICE);
    }

    @Test
    void missingDuplicateAndLongHeadersNeverCallSqlOrWrite() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload(DEVICE)))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY, "Bearer " + KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(payload(DEVICE)))
                .andExpect(status().isUnauthorized());
        ingest("delta_dev_" + "a".repeat(248), DEVICE).andExpect(status().isUnauthorized());
        assertThat(SQL_CALLS).hasValue(0);
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void acceptsKeyAt256CharacterBoundary() throws Exception {
        String maxKey = "delta_dev_" + "a".repeat(246);
        validKeys = Set.of(maxKey);
        ingest(maxKey, DEVICE).andExpect(status().isCreated());
        assertThat(SQL_CALLS).hasValue(1);
        assertThat(receivedApiKey).hasSize(256);
    }

    @Test
    void unknownRevokedOrExpiredKeysCannotWrite() throws Exception {
        validKeys = Set.of();
        for (String key : List.of("delta_dev_unknown", "delta_dev_revoked", "delta_dev_expired")) {
            ingest(key, DEVICE).andExpect(status().isUnauthorized());
        }
        assertThat(SQL_CALLS).hasValue(3);
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void revocationBetweenRequestsPreventsSecondWrite() throws Exception {
        ingest(KEY, DEVICE).andExpect(status().isCreated());
        validKeys = Set.of();
        ingest(KEY, DEVICE).andExpect(status().isUnauthorized());
        assertThat(SQL_CALLS).hasValue(2);
        assertThat(stored).hasSize(1);
        verify(repository, times(1)).save(any(PulseDocument.class));
    }

    @Test
    void rotationRejectsOldKeyAndAcceptsNewKey() throws Exception {
        ingest(KEY, DEVICE).andExpect(status().isCreated());
        validKeys = Set.of(NEW_KEY);
        ingest(KEY, DEVICE).andExpect(status().isUnauthorized());
        ingest(NEW_KEY, DEVICE).andExpect(status().isCreated());
        assertThat(SQL_CALLS).hasValue(3);
        assertThat(stored).hasSize(2);
    }

    @Test
    void inactiveOrDeletedDeviceIsRejectedAndReactivationRevalidates() throws Exception {
        active = false;
        ingest(KEY, DEVICE).andExpect(status().isUnauthorized());
        active = true;
        ingest(KEY, DEVICE).andExpect(status().isCreated());
        assertThat(SQL_CALLS).hasValue(2);
        assertThat(stored).hasSize(1);
    }

    @Test
    void missingWritePermissionDoesNotReachPersistence() throws Exception {
        sqlBody = authorizedBody().replace("\"telemetry:write\"", "\"telemetry:read\"");
        ingest(KEY, DEVICE).andExpect(status().isForbidden());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ESP321", "esp00321", "OTHER", "ESP00321 "})
    void identityMismatchRejectsEntireBatchBeforeAnyWrite(String identity) throws Exception {
        ingest(KEY, identity).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("device_id deve corresponder ao dispositivo autenticado."));
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503})
    void sqlHttpFailuresNeverWriteAndHideInternalResponse(int code, CapturedOutput output) throws Exception {
        sqlStatus = code;
        sqlBody = KEY + SERVICE;
        var response = ingest(KEY, DEVICE).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(KEY, SERVICE, "127.0.0.1");
        assertThat(output.getAll()).doesNotContain(KEY, SERVICE);
        assertThat(SQL_CALLS).hasValue(1);
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "{\"valid\":\"true\"}", "{\"valid\":true}",
            "{\"valid\":true,\"device_id\":321,\"credential_id\":1,\"permissions\":[\"telemetry:write\"]}",
            "{\"valid\":true,\"device_id\":\"ESP00321\",\"credential_id\":0,\"permissions\":[\"telemetry:write\"]}",
            "{\"valid\":true,\"device_id\":\"ESP00321\",\"credential_id\":1,\"permissions\":[null]}"})
    void unexpectedSqlResponseNeverWrites(String response) throws Exception {
        sqlBody = response;
        ingest(KEY, DEVICE).andExpect(status().isServiceUnavailable());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void timeoutNeverWrites() throws Exception {
        sqlDelay = 1500;
        ingest(KEY, DEVICE).andExpect(status().isServiceUnavailable());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void stalledResponseBodyIsAlsoBoundedAndCannotWrite() throws Exception {
        sqlBodyDelay = 1500;
        ingest(KEY, DEVICE).andExpect(status().isServiceUnavailable());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void connectionClosedBySqlCannotWrite() throws Exception {
        closeConnection = true;
        ingest(KEY, DEVICE).andExpect(status().isServiceUnavailable());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void businessValidationStillRejectsInvalidBatchBeforeWriting() throws Exception {
        mvc.perform(post(PATH).header("Authorization", "Bearer " + KEY).contentType(MediaType.APPLICATION_JSON)
                        .content(payload(DEVICE).replace("\"total_pulses\":2", "\"total_pulses\":3")))
                .andExpect(status().isBadRequest());
        assertThat(stored).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void readDoesNotRequireKeyOrSqlEvenWhenSqlIsUnavailable() throws Exception {
        sqlStatus = 503;
        ObjectId id = new ObjectId();
        when(repository.findById(id)).thenReturn(Optional.of(new PulseDocument(id, DEVICE,
                java.time.Instant.parse("2026-09-27T17:13:06Z"), 5, 0, List.of())));
        mvc.perform(get(PATH + "/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.device_id").value(DEVICE));
        assertThat(SQL_CALLS).hasValue(0);
        assertThat(stored).isEmpty();
    }
}
