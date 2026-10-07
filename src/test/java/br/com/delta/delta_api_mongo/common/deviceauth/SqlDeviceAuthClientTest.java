package br.com.delta.delta_api_mongo.common.deviceauth;

import br.com.delta.delta_api_mongo.common.config.DeviceAuthProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SqlDeviceAuthClientTest {
    private static final String SERVICE = "delta_svc_" + "s".repeat(43);
    private static final String KEY = "delta_dev_secret-for-test";
    private HttpServer server;
    private ExecutorService executor;
    private SqlDeviceAuthClient client;
    private int status = 200;
    private String body = """
            {"valid":true,"device_id":"ESP00321","credential_id":1,"permissions":["telemetry:write"]}
            """;
    private long delay;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/delta/internal/device-auth/validate", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        client = new SqlDeviceAuthClient(properties(Duration.ofSeconds(2)));
    }

    private DeviceAuthProperties properties(Duration responseTimeout) {
        return new DeviceAuthProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                SERVICE, Duration.ofSeconds(1), responseTimeout);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void usesServiceHeaderAndDeviceKeyBodyPreservingCanonicalIdentity() {
        var device = client.validate(KEY).orElseThrow();
        assertThat(device.deviceId()).isEqualTo("ESP00321");
        assertThat(device.credentialId()).isEqualTo(1);
        assertThat(device.permissions()).containsExactly("telemetry:write");
        assertThat(authorization.get()).isEqualTo("Bearer " + SERVICE);
        assertThat(contentType.get()).isEqualTo("application/json");
        var json = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(json.path("api_key").stringValue()).isEqualTo(KEY);
        assertThat(requestBody.get()).doesNotContain(SERVICE);
    }

    @Test
    void doesNotCacheValidationOrRejections() {
        assertThat(client.validate(KEY)).isPresent();
        body = "{\"valid\":false}";
        assertThat(client.validate(KEY)).isEmpty();
        body = "{\"valid\":true,\"device_id\":\"ESP00321\",\"credential_id\":2,\"permissions\":[]}";
        assertThat(client.validate(KEY).orElseThrow().permissions()).isEmpty();
        assertThat(requests).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503, 302, 204})
    void upstreamErrorsAreUnavailableAndNeverExposeBodies(int code, CapturedOutput output) {
        status = code;
        body = KEY + SERVICE;
        assertThatThrownBy(() -> client.validate(KEY))
                .isInstanceOf(DeviceAuthUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(KEY).hasMessageNotContaining(SERVICE);
        assertThat(output.getAll()).contains("http_" + code).doesNotContain(KEY, SERVICE);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not json", "null", "[]", "{}", "{\"valid\":\"true\"}", "{\"valid\":1}",
            "{\"valid\":null}", "{\"valid\":true}",
            "{\"valid\":true,\"device_id\":1,\"credential_id\":1,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"\",\"credential_id\":1,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\" \",\"credential_id\":1,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":\"1\",\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1.0,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":0,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":-1,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":9999999999999999999999,\"permissions\":[]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1,\"permissions\":null}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1,\"permissions\":\"telemetry:write\"}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1,\"permissions\":[null]}",
            "{\"valid\":true,\"device_id\":\"D\",\"credential_id\":1,\"permissions\":[1]}",
            "{\"valid\":false} {}", "{\"valid\":false,\"valid\":true}"
    })
    void rejectsUnexpectedResponse(String response, CapturedOutput output) {
        body = response;
        assertThatThrownBy(() -> client.validate(KEY)).isInstanceOf(DeviceAuthUnavailableException.class);
        assertThat(output.getAll()).doesNotContain(KEY, SERVICE, response);
    }

    @Test
    void timesOutWithoutRetry() {
        delay = 500;
        client = new SqlDeviceAuthClient(properties(Duration.ofMillis(100)));
        assertThatThrownBy(() -> client.validate(KEY)).isInstanceOf(DeviceAuthUnavailableException.class);
        assertThat(requests.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void connectionFailureDoesNotCarryTransportDetails() {
        server.stop(0);
        assertThatThrownBy(() -> client.validate(KEY))
                .isInstanceOf(DeviceAuthUnavailableException.class).hasNoCause();
    }

    @Test
    void validatesMandatoryConfigurationWithoutPrintingServiceSecret() {
        assertThat(properties(Duration.ofSeconds(1)).toString()).doesNotContain(SERVICE);
        for (String secret : new String[] {"", "delta_dev_wrong", "delta_svc_short", SERVICE + "!"}) {
            var invalid = new DeviceAuthProperties(URI.create("http://localhost:8080"), secret,
                    Duration.ofSeconds(2), Duration.ofSeconds(5));
            assertThatThrownBy(() -> new SqlDeviceAuthClient(invalid))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining(SERVICE);
        }
        for (String url : new String[] {"http://sql.example.com", "ftp://localhost", "https://user:pass@sql.example.com",
                "https://sql.example.com?credential=hidden", "https://sql.example.com#fragment"}) {
            assertThatThrownBy(() -> new SqlDeviceAuthClient(new DeviceAuthProperties(URI.create(url), SERVICE,
                    Duration.ofSeconds(2), Duration.ofSeconds(5)))).isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new SqlDeviceAuthClient(new DeviceAuthProperties(null, SERVICE,
                Duration.ofSeconds(2), Duration.ofSeconds(5)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SqlDeviceAuthClient(properties(Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }
}
