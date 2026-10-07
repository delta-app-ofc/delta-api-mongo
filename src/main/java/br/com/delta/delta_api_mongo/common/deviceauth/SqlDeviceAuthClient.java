package br.com.delta.delta_api_mongo.common.deviceauth;

import br.com.delta.delta_api_mongo.common.config.DeviceAuthProperties;
import br.com.delta.delta_api_mongo.common.exception.DeviceAuthUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

public final class SqlDeviceAuthClient {
    private static final Logger LOG = LoggerFactory.getLogger(SqlDeviceAuthClient.class);
    private final DeviceAuthProperties properties;
    private final URI endpoint;
    private final RestClient http;
    private final JsonMapper json = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
            .build();

    public SqlDeviceAuthClient(DeviceAuthProperties properties) {
        properties.validate();
        this.properties = properties;
        endpoint = URI.create(properties.sqlApiBaseUrl().toString().replaceAll("/+$", "")
                + "/delta/internal/device-auth/validate");
        var transport = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var requestFactory = new JdkClientHttpRequestFactory(transport);
        // Spring bounds the response, including reading its body.
        requestFactory.setReadTimeout(properties.responseTimeout());
        http = RestClient.builder().requestFactory(requestFactory).build();
    }

    public Optional<AuthenticatedDevice> validate(String apiKey) {
        try {
            return http.post().uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceCredential())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(json.writeValueAsString(Map.of("api_key", apiKey)))
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status != 200) {
                            throw unavailable("http_" + status);
                        }
                        return parse(new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8));
                    });
        } catch (RestClientException exception) {
            // Never retain HTTP exceptions, request data or response bodies.
            throw unavailable(isTimeout(exception) ? "timeout" : "transport");
        }
    }

    private boolean isTimeout(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException || cause instanceof SocketTimeoutException) {
                return true;
            }
        }
        return false;
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
