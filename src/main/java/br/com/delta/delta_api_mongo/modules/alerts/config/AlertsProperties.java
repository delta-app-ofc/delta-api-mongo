package br.com.delta.delta_api_mongo.modules.alerts.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * API de alertas (prefixo delta.alerts). Desligada por padrão.
 * - auth: dados para validar o JWT RS256 que o back-end emite para o usuário (mesma chave pública da IA).
 */
@ConfigurationProperties(prefix = "delta.alerts")
public record AlertsProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue Auth auth
) {
    /** publicKey: PEM X.509 inline ou caminho de arquivo (com ou sem file:). */
    public record Auth(
            @DefaultValue("") String publicKey,
            @DefaultValue("delta-api") String issuer,
            @DefaultValue("delta-api") String audience,
            @DefaultValue("30") int clockSkewSeconds
    ) {
    }
}
