package br.com.delta.delta_api_mongo.modules.alerts.security;

import br.com.delta.delta_api_mongo.modules.alerts.config.AlertsProperties;
import br.com.delta.delta_api_mongo.modules.alerts.TestKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static br.com.delta.delta_api_mongo.modules.alerts.TestKeys.*;
import static org.assertj.core.api.Assertions.assertThat;

class JwtVerifierTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(NOW_MS), ZoneOffset.UTC);

    private static JwtVerifier verifier(String publicKey) {
        return new JwtVerifier(new AlertsProperties(true, "",
                new AlertsProperties.Auth(publicKey, "delta-api", "delta-api", 30)), CLOCK);
    }

    @Test
    void aValidTokenReturnsTheUserIdFromTheSubject() throws Exception {
        assertThat(verifier(PUBLIC_PEM).verify(token("212"))).contains(212);
    }

    @Test
    void theKeyCanComeFromAFileWithOrWithoutTheFilePrefix(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("public.pem");
        Files.writeString(file, PUBLIC_PEM);

        assertThat(verifier(file.toString()).verify(token("7"))).contains(7);
        assertThat(verifier("file:" + file).verify(token("7"))).contains(7);
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRejected() throws Exception {
        var forged = sign(OTHER_PAIR, claims("212", "delta-api", "delta-api", NOW_MS + 60_000, NOW_MS - 1000));

        assertThat(verifier(PUBLIC_PEM).verify(forged)).isEmpty();
    }

    @Test
    void aTamperedPayloadIsRejected() throws Exception {
        String[] parts = token("7").split("\\.");
        String otherPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"delta-api\",\"aud\":\"delta-api\",\"sub\":\"999\",\"iat\":1799999999,\"exp\":1800003600}")
                        .getBytes());

        assertThat(verifier(PUBLIC_PEM).verify(parts[0] + "." + otherPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void anExpiredTokenIsRejectedButTheClockSkewIsTolerated() throws Exception {
        var justExpired = sign(PAIR, claims("1", "delta-api", "delta-api", NOW_MS - 10_000, NOW_MS - 100_000));
        var longExpired = sign(PAIR, claims("1", "delta-api", "delta-api", NOW_MS - 60_000, NOW_MS - 100_000));

        assertThat(verifier(PUBLIC_PEM).verify(justExpired)).contains(1);
        assertThat(verifier(PUBLIC_PEM).verify(longExpired)).isEmpty();
    }

    @Test
    void tokensIssuedInTheFutureRespectTheClockSkew() throws Exception {
        var tolerated = sign(PAIR, claims("1", "delta-api", "delta-api", NOW_MS + 120_000, NOW_MS + 30_000));
        var future = sign(PAIR, claims("1", "delta-api", "delta-api", NOW_MS + 120_000, NOW_MS + 31_000));

        assertThat(verifier(PUBLIC_PEM).verify(tolerated)).contains(1);
        assertThat(verifier(PUBLIC_PEM).verify(future)).isEmpty();
    }

    @Test
    void tokensCannotBeUsedBeforeTheirNotBeforeTimeBeyondTheClockSkew() throws Exception {
        var base = claims("1", "delta-api", "delta-api", NOW_MS + 120_000, NOW_MS - 1000);
        var tolerated = sign(PAIR, new com.nimbusds.jwt.JWTClaimsSet.Builder(base)
                .notBeforeTime(new java.util.Date(NOW_MS + 30_000)).build());
        var future = sign(PAIR, new com.nimbusds.jwt.JWTClaimsSet.Builder(base)
                .notBeforeTime(new java.util.Date(NOW_MS + 31_000)).build());

        assertThat(verifier(PUBLIC_PEM).verify(tolerated)).contains(1);
        assertThat(verifier(PUBLIC_PEM).verify(future)).isEmpty();
    }

    @Test
    void theIssuerAndTheAudienceMustMatch() throws Exception {
        var wrongIssuer = sign(PAIR, claims("1", "outro", "delta-api", NOW_MS + 60_000, NOW_MS - 1000));
        var wrongAudience = sign(PAIR, claims("1", "delta-api", "outro", NOW_MS + 60_000, NOW_MS - 1000));

        assertThat(verifier(PUBLIC_PEM).verify(wrongIssuer)).isEmpty();
        assertThat(verifier(PUBLIC_PEM).verify(wrongAudience)).isEmpty();
    }

    @Test
    void theExpirationAndTheIssueTimeAreRequired() throws Exception {
        var noExpiration = sign(PAIR, claims("1", "delta-api", "delta-api", 0, NOW_MS - 1000));
        var noIssueTime = sign(PAIR, new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("delta-api")
                .audience("delta-api").subject("1").expirationTime(new java.util.Date(NOW_MS + 60_000)).build());

        assertThat(verifier(PUBLIC_PEM).verify(noExpiration)).isEmpty();
        assertThat(verifier(PUBLIC_PEM).verify(noIssueTime)).isEmpty();
    }

    @Test
    void theSubjectMustBeAPositiveInteger() throws Exception {
        for (String subject : new String[]{"abc", "0", "-5", "1.5", ""}) {
            var token = sign(PAIR, claims(subject, "delta-api", "delta-api", NOW_MS + 60_000, NOW_MS - 1000));
            assertThat(verifier(PUBLIC_PEM).verify(token)).as("sub=%s", subject).isEmpty();
        }
    }

    @Test
    void anHs256TokenIsRejectedEvenWithValidClaims() throws Exception {
        var token = hs256(claims("212", "delta-api", "delta-api", NOW_MS + 60_000, NOW_MS - 1000));

        assertThat(verifier(PUBLIC_PEM).verify(token)).isEmpty();
    }

    @Test
    void garbageAndEmptyTokensAreRejected() {
        var verifier = verifier(PUBLIC_PEM);

        assertThat(verifier.verify(null)).isEmpty();
        assertThat(verifier.verify("")).isEmpty();
        assertThat(verifier.verify("não é um jwt")).isEmpty();
        assertThat(verifier.verify("a.b.c")).isEmpty();
    }

    @Test
    void withoutAUsableKeyNoTokenIsAccepted() throws Exception {
        assertThat(verifier("").verify(token("212"))).isEmpty();
        assertThat(verifier("não existe.pem").verify(token("212"))).isEmpty();
        assertThat(verifier("-----BEGIN PUBLIC KEY-----\nlixo\n-----END PUBLIC KEY-----").verify(token("212"))).isEmpty();
        assertThat(TestKeys.PUBLIC_PEM).contains("BEGIN PUBLIC KEY");
    }
}
