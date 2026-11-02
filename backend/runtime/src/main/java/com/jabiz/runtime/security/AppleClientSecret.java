package com.jabiz.runtime.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The client secret of Sign in with Apple (decision D67 item 1): not a fixed text but a token this server signs (ES256)
 * with the developer's key, naming the team as issuer, the Services ID as subject and Apple as audience. Each lasts
 * {@link #LIFETIME} and is made again {@link #RENEW_BEFORE} before it ends; Apple accepts up to six months.
 */
public class AppleClientSecret {

    static final Duration LIFETIME = Duration.ofHours(1);
    static final Duration RENEW_BEFORE = Duration.ofMinutes(10);
    /** The audience Apple requires, whatever the issuer setting says. */
    static final String AUDIENCE = OidcProvider.APPLE_ISSUER;

    private record Signed(String secret, Instant expiresAt) {}

    private final Clock clock;
    private final Map<String, Signed> signed = new ConcurrentHashMap<>();

    public AppleClientSecret(Clock clock) {
        this.clock = Objects.requireNonNull(clock);
    }

    /** The provider's current secret, signed anew when the last one is about to end. */
    String secretOf(OidcProvider provider) {
        Instant now = clock.instant();
        Signed current = signed.get(provider.id());
        if (current == null || !now.plus(RENEW_BEFORE).isBefore(current.expiresAt())) {
            current = new Signed(sign(provider.apple(), provider.clientId(), now), now.plus(LIFETIME));
            signed.put(provider.id(), current);
        }
        return current.secret();
    }

    /** A secret valid for {@link #LIFETIME} from {@code now}. */
    static String sign(OidcProvider.Apple apple, String clientId, Instant now) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(apple.keyId()).build(),
                new JWTClaimsSet.Builder().issuer(apple.teamId()).subject(clientId).audience(AUDIENCE)
                    .issueTime(Date.from(now)).expirationTime(Date.from(now.plus(LIFETIME))).build());
            jwt.sign(new ECDSASigner(privateKey(apple.privateKey())));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Cannot sign the client secret of Sign in with Apple", e);
        }
    }

    /**
     * The EC P-256 key of a {@code .p8} file: its PEM text (line breaks as such or written {@code \n}, as an
     * environment variable may hold them) or the Base64 body alone.
     *
     * @throws IllegalArgumentException when it is not such a key; the message never contains the key
     */
    public static ECPrivateKey privateKey(String text) {
        String body = text.replace("\\n", "\n")
            .replaceAll("-----(BEGIN|END) [A-Z ]*PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
        byte[] der;
        try {
            der = Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("not a PEM or Base64 PKCS #8 key");
        }
        try {
            if (KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der)) instanceof ECPrivateKey ec
                && p256(ec.getParams())) {
                return ec;
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("not a PKCS #8 EC private key");
        }
        throw new IllegalArgumentException("not an EC P-256 key");
    }

    private static boolean p256(ECParameterSpec params) {
        return params != null && params.getCurve().getField().getFieldSize() == 256
            && params.getOrder().equals(com.nimbusds.jose.jwk.Curve.P_256.toECParameterSpec().getOrder());
    }
}
