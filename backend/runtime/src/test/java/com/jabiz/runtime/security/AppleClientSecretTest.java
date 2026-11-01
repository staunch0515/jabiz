package com.jabiz.runtime.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The client secret of Sign in with Apple (decision D67 item 1) and the reading of the developer's key. */
class AppleClientSecretTest {

    private static final Instant NOW = Instant.parse("2026-11-01T09:00:00Z");

    private static String pem(ECKey key, String lineBreak) throws Exception {
        return "-----BEGIN PRIVATE KEY-----" + lineBreak
            + Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded()) + lineBreak
            + "-----END PRIVATE KEY-----";
    }

    private static OidcProvider provider(String key) {
        return new OidcProvider("apple", null, "com.example.web", null, null, null, null, null,
            Map.of("app", "https://app.example.com/login/oidc"), "apple", false, false,
            new OidcProvider.Apple("TEAM123456", "KEY1234567", key, "https://app.example.com/api/auth/oidc/apple/form-post"));
    }

    @Test
    void theSecretIsSignedWithTheDevelopersKeyForApple() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        SignedJWT jwt = SignedJWT.parse(AppleClientSecret.sign(provider(pem(key, "\n")).apple(), "com.example.web",
            NOW));
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.ES256);
        assertThat(jwt.getHeader().getKeyID()).isEqualTo("KEY1234567");
        assertThat(jwt.verify(new ECDSAVerifier(key.toPublicJWK()))).isTrue();
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("TEAM123456");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("com.example.web");
        assertThat(jwt.getJWTClaimsSet().getAudience()).isEqualTo(List.of("https://appleid.apple.com"));
        assertThat(jwt.getJWTClaimsSet().getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(jwt.getJWTClaimsSet().getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofHours(1)));
    }

    @Test
    void theSecretIsKeptUntilShortlyBeforeItEnds() throws Exception {
        AtomicReference<Instant> now = new AtomicReference<>(NOW);
        Clock clock = new Clock() {
            @Override
            public java.time.ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        AppleClientSecret secrets = new AppleClientSecret(clock);
        OidcProvider provider = provider(pem(new ECKeyGenerator(Curve.P_256).generate(), "\\n"));
        String first = secrets.secretOf(provider);
        now.set(NOW.plus(Duration.ofMinutes(49)));
        assertThat(secrets.secretOf(provider)).isEqualTo(first);
        now.set(NOW.plus(Duration.ofMinutes(50)));
        assertThat(secrets.secretOf(provider)).isNotEqualTo(first);
    }

    @Test
    void theKeyIsReadAsPemWithEitherLineBreakOrAsItsBodyAndMustBeP256() throws Exception {
        ECKey key = new ECKeyGenerator(Curve.P_256).generate();
        String body = Base64.getEncoder().encodeToString(key.toECPrivateKey().getEncoded());
        for (String text : List.of(pem(key, "\n"), pem(key, "\\n"), body)) {
            assertThat(AppleClientSecret.privateKey(text).getS()).isEqualTo(key.toECPrivateKey().getS());
        }
        assertThatThrownBy(() -> AppleClientSecret.privateKey("not a key!"))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("not a PEM or Base64 PKCS #8 key");
        assertThatThrownBy(() -> AppleClientSecret.privateKey(Base64.getEncoder().encodeToString(new byte[] {1, 2})))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("not a PKCS #8 EC private key");
        ECKey p384 = new ECKeyGenerator(Curve.P_384).generate();
        assertThatThrownBy(() -> AppleClientSecret.privateKey(pem(p384, "\n")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("not an EC P-256 key");
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        String rsaBody = Base64.getEncoder().encodeToString(rsa.generateKeyPair().getPrivate().getEncoded());
        assertThatThrownBy(() -> AppleClientSecret.privateKey(rsaBody)).isInstanceOf(IllegalArgumentException.class)
            .hasMessage("not a PKCS #8 EC private key");
    }

    @Test
    void theNameOfTheFirstAnswerIsComposedAsItIsWritten() {
        assertThat(AppleUser.name("{\"name\":{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\"},\"email\":\"a@b.c\"}"))
            .isEqualTo("Ada Lovelace");
        assertThat(AppleUser.name("{\"name\":{\"firstName\":\"太郎\",\"lastName\":\"山田\"}}")).isEqualTo("山田太郎");
        assertThat(AppleUser.name("{\"name\":{\"firstName\":\"민준\",\"lastName\":\"김\"}}")).isEqualTo("김민준");
        assertThat(AppleUser.name("{\"name\":{\"firstName\":\"Ada\"}}")).isEqualTo("Ada");
        assertThat(AppleUser.name("{\"name\":{\"lastName\":\" \\u0000Lovelace \"}}")).isEqualTo("Lovelace");
        assertThat(AppleUser.name("{\"email\":\"a@b.c\"}")).isNull();
        assertThat(AppleUser.name("{\"name\":\"Ada\"}")).isNull();
        assertThat(AppleUser.name("not json")).isNull();
        assertThat(AppleUser.name(null)).isNull();
        assertThat(AppleUser.name("{\"name\":{\"firstName\":\"" + "x".repeat(5000) + "\"}}")).isNull();
    }
}
