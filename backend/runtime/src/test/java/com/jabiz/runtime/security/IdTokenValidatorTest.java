package com.jabiz.runtime.security;

import com.jabiz.runtime.test.TestOidcProvider;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the platform takes from an ID token for opening and linking accounts (phase 16f; decision D39). */
class IdTokenValidatorTest {

    private static final String ISSUER = "https://idp.example.com";
    private static final Instant NOW = Instant.parse("2026-10-13T09:00:00Z");
    private static final RSAKey KEY = key();

    private static RSAKey key() {
        try {
            return new RSAKeyGenerator(2048).keyID("k1").generate();
        } catch (com.nimbusds.jose.JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static IdTokenValidator.Identity identity(UnaryOperator<JWTClaimsSet.Builder> change) {
        JWTClaimsSet claims = change.apply(new JWTClaimsSet.Builder().issuer(ISSUER).subject("s-1")
            .audience("client").issueTime(Date.from(NOW)).expirationTime(Date.from(NOW.plusSeconds(300)))
            .claim("nonce", "n")).build();
        String token = TestOidcProvider.sign(claims, JWSAlgorithm.RS256, KEY);
        return IdTokenValidator.validate(IdTokenValidator.parse(token), new JWKSet(KEY.toPublicJWK()), ISSUER,
            "client", OidcStateStore.hash("n"), NOW);
    }

    private static OidcProvider provider(String kind) {
        return new OidcProvider("p", ISSUER, "client", "s", "https://erp.example.com/login/oidc", null, null, null,
            null, kind, true);
    }

    @Test
    void theAddressCountsOnlyWhenTheProviderSaysItVerifiedIt() {
        OidcProvider generic = provider(null);
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", true))
            .trustedEmail(generic)).isEqualTo("a@example.com");
        // Some providers send the text.
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", "TRUE"))
            .trustedEmail(generic)).isEqualTo("a@example.com");
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", false))
            .trustedEmail(generic)).isNull();
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", "yes"))
            .trustedEmail(generic)).isNull();
        assertThat(identity(c -> c.claim("email", "a@example.com")).trustedEmail(generic)).isNull();
        assertThat(identity(c -> c.claim("email_verified", true)).trustedEmail(generic)).isNull();
        // Something that is no address is none.
        assertThat(identity(c -> c.claim("email", "not an address").claim("email_verified", true)).email()).isNull();
        assertThat(identity(c -> c.claim("email", List.of("a@example.com"))).email()).isNull();
    }

    @Test
    void googleVouchesForItsOwnAddressesAndWorkspaceAccountsOnly() {
        OidcProvider google = provider("google");
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", true))
            .trustedEmail(google)).isNull();
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", true)
            .claim("hd", "example.com")).trustedEmail(google)).isEqualTo("a@example.com");
        assertThat(identity(c -> c.claim("email", "A@GMail.com").claim("email_verified", true))
            .trustedEmail(google)).isEqualTo("A@GMail.com");
        assertThat(identity(c -> c.claim("email", "a@googlemail.com").claim("email_verified", true))
            .trustedEmail(google)).isEqualTo("a@googlemail.com");
        assertThat(identity(c -> c.claim("email", "a@gmail.com").claim("email_verified", false))
            .trustedEmail(google)).isNull();
        // A provider at Google's issuer gets Google's rule whatever its kind says.
        OidcProvider legacy = new OidcProvider("p", OidcProvider.GOOGLE_ISSUER, "client", "s",
            "https://erp.example.com/login/oidc", null, null, null, null, null, true);
        assertThat(identity(c -> c.claim("email", "a@example.com").claim("email_verified", true))
            .trustedEmail(legacy)).isNull();
        assertThat(identity(c -> c.claim("email", "a@gmail.com").claim("email_verified", true))
            .trustedEmail(legacy)).isEqualTo("a@gmail.com");
    }

    @Test
    void theNameIsCleanedAndCut() {
        assertThat(identity(c -> c.claim("name", "  Ada\u0000\n Lovelace​ ")).name()).isEqualTo("Ada Lovelace");
        assertThat(identity(c -> c.claim("name", "\u0007 ")).name()).isNull();
        assertThat(identity(c -> c.claim("name", Map.of("first", "Ada"))).name()).isNull();
        assertThat(identity(c -> c.claim("name", "x".repeat(250))).name()).hasSize(IdTokenValidator.MAX_NAME);
        // Never half a character: the cut stays before a surrogate pair it would split.
        assertThat(identity(c -> c.claim("name", "x".repeat(199) + "😀" + "y")).name())
            .hasSize(IdTokenValidator.MAX_NAME - 1);
        assertThat(identity(c -> c).name()).isNull();
    }

    @Test
    void theIdentityKeepsItsValuesOutOfItsText() {
        IdTokenValidator.Identity identity = identity(c -> c.claim("email", "secret@example.com")
            .claim("email_verified", true).claim("name", "Ada"));
        assertThat(identity.toString()).contains("s-1").doesNotContain("secret", "Ada");
        assertThat(identity.emailVerified()).isTrue();
        assertThat(new IdTokenValidator.Identity("s", List.of(), null).trustedEmail(provider(null))).isNull();
    }
    private static final String SECRET = "a-channel-secret-of-thirty-two-b";

    private static JWTClaimsSet claims() {
        return new JWTClaimsSet.Builder().issuer(ISSUER).subject("s-1").audience("client").issueTime(Date.from(NOW))
            .expirationTime(Date.from(NOW.plusSeconds(300))).claim("nonce", "n").claim("email", "a@example.com")
            .build();
    }

    private static IdTokenValidator.Identity validate(String token, String hmacSecret) {
        return IdTokenValidator.validate(IdTokenValidator.parse(token), new JWKSet(KEY.toPublicJWK()), hmacSecret,
            ISSUER, "client", OidcStateStore.hash("n"), NOW);
    }

    @Test
    void hs256IsAcceptedOnlyWithTheClientSecretOfAProviderThatSignsSo() {
        // LINE's web sign-in (decision D60 item 2): keyed with the client secret.
        String token = TestOidcProvider.sign(claims(), JWSAlgorithm.HS256,
            SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(IdTokenValidator.hs256(IdTokenValidator.parse(token))).isTrue();
        assertThat(validate(token, SECRET).email()).isEqualTo("a@example.com");
        // Another secret verifies nothing; without a secret (every other provider) the algorithm is refused.
        assertThatThrownBy(() -> validate(token, SECRET.replace('a', 'b')))
            .isInstanceOf(IdTokenValidator.InvalidIdTokenException.class).hasMessageContaining("signature");
        assertThatThrownBy(() -> validate(token, null))
            .isInstanceOf(IdTokenValidator.InvalidIdTokenException.class).hasMessageContaining("algorithm");
        // The other checks still apply to an HS256 token.
        String expired = TestOidcProvider.sign(new JWTClaimsSet.Builder(claims())
            .expirationTime(Date.from(NOW.minusSeconds(120))).build(), JWSAlgorithm.HS256,
            SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validate(expired, SECRET)).hasMessageContaining("Expired");
        String otherClient = TestOidcProvider.sign(new JWTClaimsSet.Builder(claims()).audience("other").build(),
            JWSAlgorithm.HS256, SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validate(otherClient, SECRET)).hasMessageContaining("another client");
    }

    @Test
    void anHmacKeyedWithThePublicKeyOrNoSignatureAtAllIsRefused() throws Exception {
        // The classic confusion: HS256 keyed with the provider's public key, which anybody has.
        byte[] publicKey = KEY.toRSAPublicKey().getEncoded();
        String confused = TestOidcProvider.sign(claims(), JWSAlgorithm.HS256, publicKey);
        assertThatThrownBy(() -> validate(confused, null)).hasMessageContaining("algorithm");
        assertThatThrownBy(() -> validate(confused, SECRET)).hasMessageContaining("signature");
        // An unsigned token is no signed JWT at all.
        String none = new com.nimbusds.jwt.PlainJWT(claims()).serialize();
        assertThatThrownBy(() -> IdTokenValidator.parse(none))
            .isInstanceOf(IdTokenValidator.InvalidIdTokenException.class);
        // A secret too short to be an HS256 key verifies nothing (RFC 7518 section 3.2).
        String shortKeyed = TestOidcProvider.sign(claims(), JWSAlgorithm.HS256, SECRET.getBytes(
            java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> validate(shortKeyed, "short")).hasMessageContaining("signature");
    }

    @Test
    void anRs256TokenOfALineProviderIsStillCheckedWithItsKeys() {
        String token = TestOidcProvider.sign(claims(), JWSAlgorithm.RS256, KEY);
        assertThat(validate(token, SECRET).subject()).isEqualTo("s-1");
    }
}
