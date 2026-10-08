package br.com.delta.delta_api_mongo.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

@ConfigurationProperties(prefix = "delta.device-auth")
public record DeviceAuthProperties(
        URI sqlApiBaseUrl,
        String serviceCredential,
        Duration connectTimeout,
        Duration responseTimeout
) {
    public void validate() {
        if (sqlApiBaseUrl == null || sqlApiBaseUrl.getHost() == null
                || sqlApiBaseUrl.getUserInfo() != null || sqlApiBaseUrl.getQuery() != null
                || sqlApiBaseUrl.getFragment() != null) {
            throw new IllegalStateException("SQL_API_BASE_URL deve ser uma URL HTTP(S) de servidor válida.");
        }
        boolean local = Set.of("localhost", "127.0.0.1", "[::1]").contains(sqlApiBaseUrl.getHost());
        if (!"https".equalsIgnoreCase(sqlApiBaseUrl.getScheme())
                && !(local && "http".equalsIgnoreCase(sqlApiBaseUrl.getScheme()))) {
            throw new IllegalStateException("SQL_API_BASE_URL exige HTTPS fora do desenvolvimento local.");
        }
        if (serviceCredential == null || !serviceCredential.matches("delta_svc_[A-Za-z0-9_-]{43,128}")) {
            throw new IllegalStateException("MONGO_SERVICE_CREDENTIAL ausente ou fora do formato esperado.");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || responseTimeout == null || responseTimeout.isNegative() || responseTimeout.isZero()
                || responseTimeout.toMillis() == 0) {
            throw new IllegalStateException("Os timeouts de autenticação devem ser positivos e finitos.");
        }
    }

    @Override
    public String toString() {
        return "DeviceAuthProperties[credentials=REDACTED]";
    }
}
