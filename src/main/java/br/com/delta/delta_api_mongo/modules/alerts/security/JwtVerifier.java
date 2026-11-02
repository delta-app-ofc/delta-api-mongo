package br.com.delta.delta_api_mongo.modules.alerts.security;

import br.com.delta.delta_api_mongo.modules.alerts.config.AlertsProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

/**
 * Confere o JWT do usuário emitido pelo back-end (delta-api-postgres): assinatura RS256 com a chave pública,
 * emissor, audiência e validade. Devolve o id do usuário (claim sub). Não consulta banco: se o usuário foi
 * desativado, o token continua valendo até expirar (igual à IA, que também só confere o token).
 * Sem chave pública configurada, nenhum token é aceito.
 */
@Component
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class JwtVerifier {
    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);

    private final AlertsProperties.Auth config;
    private final RSAPublicKey publicKey;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public JwtVerifier(AlertsProperties properties, Clock clock) {
        this.config = properties.auth();
        this.clock = clock;
        this.publicKey = loadKey(config.publicKey());
    }

    public Optional<Integer> verify(String token) {
        if (publicKey == null || token == null || token.isBlank()) return Optional.empty();
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            // Aceita só RS256: bloqueia "none" e troca de algoritmo (ex.: HS256 usando a chave pública como segredo).
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) return Optional.empty();
            if (!jwt.verify(new RSASSAVerifier(publicKey))) return Optional.empty();
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            if (!config.issuer().equals(claims.getIssuer())) return Optional.empty();
            if (claims.getAudience() == null || !claims.getAudience().contains(config.audience())) return Optional.empty();
            Date expiration = claims.getExpirationTime();
            if (expiration == null || claims.getIssueTime() == null) return Optional.empty();
            long now = clock.millis();
            if (expiration.getTime() + config.clockSkewSeconds() * 1000L <= now) return Optional.empty();
            if (claims.getSubject() == null) return Optional.empty();
            int userId = Integer.parseInt(claims.getSubject());
            return userId > 0 ? Optional.of(userId) : Optional.empty();
        } catch (Exception exception) {
            // Token malformado, assinatura inválida ou sub não numérico: simplesmente não autenticado.
            return Optional.empty();
        }
    }

    private static RSAPublicKey loadKey(String value) {
        if (value == null || value.isBlank()) {
            log.warn("delta.alerts.auth.public-key não configurada: a API de alertas rejeitará todos os tokens.");
            return null;
        }
        try {
            String pem = value.contains("BEGIN PUBLIC KEY") ? value
                    : Files.readString(Path.of(value.startsWith("file:") ? value.substring(5) : value),
                    StandardCharsets.UTF_8);
            String base64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            var spec = new X509EncodedKeySpec(Base64.getDecoder().decode(base64));
            var key = KeyFactory.getInstance("RSA").generatePublic(spec);
            if (key instanceof RSAPublicKey rsa && rsa.getModulus().bitLength() >= 2048) return rsa;
            log.warn("Chave pública inválida: precisa ser RSA de pelo menos 2048 bits.");
        } catch (Exception exception) {
            log.warn("Não foi possível ler a chave pública do JWT: {}", exception.getClass().getSimpleName());
        }
        return null;
    }
}
