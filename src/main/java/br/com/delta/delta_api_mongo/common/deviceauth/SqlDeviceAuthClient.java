package br.com.delta.delta_api_mongo.common.deviceauth;

import br.com.delta.delta_api_mongo.common.config.DeviceAuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class SqlDeviceAuthClient {
    private static final Logger LOG = LoggerFactory.getLogger(SqlDeviceAuthClient.class);
    private final DeviceAuthProperties properties;
    private final URI endpoint;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    public SqlDeviceAuthClient(DeviceAuthProperties properties) {
        properties.validate();
        this.properties = properties;
        endpoint = URI.create(properties.sqlApiBaseUrl().toString().replaceAll("/+$", "")
                + "/delta/internal/device-auth/validate");
        http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public Optional<AuthenticatedDevice> validate(String apiKey) {
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(properties.responseTimeout())
                .header("Authorization", "Bearer " + properties.serviceCredential())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Cache-Control", "no-store")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("api_key", apiKey))))
                .build();
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        try {
            // Bounds the entire response, including a stalled response body.
            var response = pending.get(properties.responseTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() != 200) {
                throw unavailable("http_" + response.statusCode());
            }
            return parse(response.body());
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw unavailable("timeout");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw unavailable("interrupted");
        } catch (ExecutionException exception) {
            throw unavailable(exception.getCause() instanceof HttpTimeoutException ? "timeout" : "transport");
        }
    }

    private Optional<AuthenticatedDevice> parse(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null || !root.isObject() || !root.path("valid").isBoolean()) {
                throw new IllegalArgumentException();
            }
            if (!root.path("valid").booleanValue()) {
                return Optional.empty();
            }
            JsonNode device = root.path("device_id");
            JsonNode credential = root.path("credential_id");
            JsonNode permissions = root.path("permissions");
            if (!device.isString() || device.stringValue().isBlank()
                    || !credential.isIntegralNumber() || !credential.canConvertToLong()
                    || credential.longValue() <= 0 || !permissions.isArray()) {
                throw new IllegalArgumentException();
            }
            var allowed = new ArrayList<String>();
            for (JsonNode permission : permissions) {
                if (!permission.isString()) {
                    throw new IllegalArgumentException();
                }
                allowed.add(permission.stringValue());
            }
            return Optional.of(new AuthenticatedDevice(device.stringValue(), credential.longValue(), allowed));
        } catch (RuntimeException exception) {
            throw unavailable("invalid_response");
        }
    }

    private DeviceAuthUnavailableException unavailable(String category) {
        LOG.warn("Falha na autenticação SQL de dispositivo: {}", category);
        return new DeviceAuthUnavailableException();
    }
}
