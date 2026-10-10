package br.com.delta.delta_api_mongo.common.devicevalidation;

import br.com.delta.delta_api_mongo.common.config.DeviceValidationProperties;
import br.com.delta.delta_api_mongo.common.exception.DeviceValidationUnavailableException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
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
class SqlDeviceValidationClientTest {
    private static final String SERVICE = "delta_svc_" + "s".repeat(43);
    private static final String DEVICE = "ESP00321";
    private HttpServer server;
    private ExecutorService executor;
    private SqlDeviceValidationClient client;
    private int status = 200;
    private String body = "{\"valid\":true,\"device_id\":\"ESP00321\"}";
    private long delay;
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/delta/internal/devices/validate", exchange -> {
            requests.incrementAndGet();
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (delay > 0) Thread.sleep(delay);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        client = new SqlDeviceValidationClient(properties(Duration.ofSeconds(2)));
    }

    private DeviceValidationProperties properties(Duration timeout) {
        return new DeviceValidationProperties(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                SERVICE, Duration.ofSeconds(1), timeout);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    @Test
    void sendsDeviceIdWithServiceCredentialAndReceivesSqlIdentity() {
        assertThat(client.validate(DEVICE)).contains(DEVICE);
        assertThat(authorization.get()).isEqualTo("Bearer " + SERVICE);
        var json = JsonMapper.builder().build().readTree(requestBody.get());
        assertThat(json.path("device_id").stringValue()).isEqualTo(DEVICE);
        assertThat(json.size()).isEqualTo(1);
        assertThat(requestBody.get()).doesNotContain(SERVICE, "api_key");
    }

    @Test
    void revalidatesEveryRequestIncludingReactivation() {
        assertThat(client.validate(DEVICE)).contains(DEVICE);
        body = "{\"valid\":false}";
        assertThat(client.validate(DEVICE)).isEmpty();
        body = "{\"valid\":true,\"device_id\":\"ESP00321\"}";
        assertThat(client.validate(DEVICE)).contains(DEVICE);
        assertThat(requests).hasValue(3);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 429, 500, 503, 302, 204})
    void upstreamErrorsNeverExposeServiceSecrets(int code, CapturedOutput output) {
        status = code;
        body = SERVICE;
        assertThatThrownBy(() -> client.validate(DEVICE)).isInstanceOf(DeviceValidationUnavailableException.class)
                .hasNoCause().hasMessageNotContaining(SERVICE);
        assertThat(output.getAll()).contains("http_" + code).doesNotContain(SERVICE);
        assertThat(requests).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not json", "null", "[]", "{}", "{\"valid\":\"true\"}",
            "{\"valid\":true}", "{\"valid\":true,\"device_id\":321}",
            "{\"valid\":true,\"device_id\":\"\"}", "{\"valid\":true,\"device_id\":\" \"}",
            "{\"valid\":true,\"device_id\":\"OTHER\"}",
            "{\"valid\":true,\"device_id\":\"esp00321\"}",
            "{\"valid\":false} {}", "{\"valid\":false,\"valid\":true}"})
    void rejectsMalformedOrMismatchedSqlResponse(String response) {
        body = response;
        assertThatThrownBy(() -> client.validate(DEVICE)).isInstanceOf(DeviceValidationUnavailableException.class);
    }

    @Test
    void timesOutWithoutRetry() {
        delay = 500;
        client = new SqlDeviceValidationClient(properties(Duration.ofMillis(100)));
        assertThatThrownBy(() -> client.validate(DEVICE)).isInstanceOf(DeviceValidationUnavailableException.class);
        assertThat(requests.get()).isLessThanOrEqualTo(1);
    }

    @Test
    void connectionFailureHidesTransportDetails() {
        server.stop(0);
        assertThatThrownBy(() -> client.validate(DEVICE)).isInstanceOf(DeviceValidationUnavailableException.class)
                .hasNoCause();
    }

    @Test
    void validatesConfigurationWithoutExposingCredential() {
        assertThat(properties(Duration.ofSeconds(1)).toString()).doesNotContain(SERVICE);
        for (String secret : new String[] {"", "invalid", "delta_svc_short", SERVICE + "!"}) {
            assertThatThrownBy(() -> new SqlDeviceValidationClient(new DeviceValidationProperties(
                    URI.create("http://localhost:8080"), secret, Duration.ofSeconds(1), Duration.ofSeconds(2))))
                    .isInstanceOf(IllegalStateException.class);
        }
        for (String url : new String[] {"http://sql.example.com", "ftp://localhost", "https://user:pass@sql.example.com",
                "https://sql.example.com?credential=hidden", "https://sql.example.com#fragment"}) {
            assertThatThrownBy(() -> new SqlDeviceValidationClient(new DeviceValidationProperties(
                    URI.create(url), SERVICE, Duration.ofSeconds(1), Duration.ofSeconds(2))))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> new SqlDeviceValidationClient(properties(Duration.ZERO)))
                .isInstanceOf(IllegalStateException.class);
    }
}
