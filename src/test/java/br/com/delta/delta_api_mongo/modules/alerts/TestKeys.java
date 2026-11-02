package br.com.delta.delta_api_mongo.modules.alerts;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Base64;
import java.util.Date;

/** Par de chaves RSA e emissor de tokens de teste, no formato do back-end (RS256, iss/aud delta-api, sub = id). */
public final class TestKeys {
    public static final KeyPair PAIR = generate();
    public static final KeyPair OTHER_PAIR = generate();
    public static final String PUBLIC_PEM = pem(PAIR);
    public static final long NOW_MS = 1_800_000_000_000L;

    private TestKeys() {
    }

    private static KeyPair generate() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String pem(KeyPair pair) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10})
                .encodeToString(pair.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
    }

    public static String token(String subject) throws Exception {
        return sign(PAIR, claims(subject, "delta-api", "delta-api", NOW_MS + 3_600_000L, NOW_MS - 1000L));
    }

    /** Token válido agora (relógio real), para testes que não controlam o relógio. */
    public static String liveToken(String subject) throws Exception {
        long now = System.currentTimeMillis();
        return sign(PAIR, claims(subject, "delta-api", "delta-api", now + 3_600_000L, now - 1000L));
    }

    public static JWTClaimsSet claims(String subject, String issuer, String audience, long expiresAtMs, long issuedAtMs) {
        var builder = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .issueTime(new Date(issuedAtMs));
        if (expiresAtMs > 0) builder.expirationTime(new Date(expiresAtMs));
        return builder.build();
    }

    public static String sign(KeyPair pair, JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
        return jwt.serialize();
    }

    /** Token HS256 assinado com um segredo qualquer: um atacante tentando trocar o algoritmo. */
    public static String hs256(JWTClaimsSet claims) throws Exception {
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner("01234567890123456789012345678901"));
        return jwt.serialize();
    }
}
