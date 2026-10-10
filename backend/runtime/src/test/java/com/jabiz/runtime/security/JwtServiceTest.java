package com.jabiz.runtime.security;

import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.MutableClock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.PlainHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_KEY = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private static final Instant T0 = Instant.parse("2026-01-31T09:00:00Z");

    private final MutableClock clock = new MutableClock(T0);
    private final JwtService service = new JwtService(KEY, Duration.ofMinutes(15), clock);
    private final Actor alice = new Actor("alice", "t1", Set.of("CLERK"), Set.of("order.read", "order.write"));

    @Test
    void anIssuedTokenCarriesTheActor() {
        JwtService.Issued issued = service.issue(alice);

        assertThat(issued.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));
        // An actor of no entry signs in to the administration (decision D36).
        assertThat(service.verify(issued.token())).isEqualTo(alice.inEntry("admin", false));
    }

    @Test
    void tokensCarryTheEntryAndWhetherTheAddressIsVerified() throws Exception {
        Actor portal = alice.inEntry("portal", true);
        assertThat(service.verify(service.issue(portal).token())).isEqualTo(portal);
        assertThat(service.verify(service.issue(alice.inEntry("portal", false)).token()).emailVerified()).isFalse();

        // A token issued before entries existed (no entry, no email_verified claim) is the administration's.
        Actor old = service.verify(signed(new JWTClaimsSet.Builder().issuer(JwtService.ISSUER).subject("alice")
            .expirationTime(Date.from(T0.plusSeconds(60))).claim(JwtService.PERMISSIONS, java.util.List.of("p"))
            .build()));
        assertThat(old.entry()).isEqualTo("admin");
        assertThat(old.emailVerified()).isFalse();

        // Changing the entry breaks the signature.
        String token = service.issue(alice.inEntry("portal", true)).token();
        String[] parts = token.split("\\.");
        String claims = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("\"portal\"", "\"admin\"");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThatThrownBy(() -> service.verify(tampered)).isInstanceOf(JwtService.InvalidTokenException.class)
            .hasMessageContaining("signature");
    }

    @Test
    void challengesCarryTheirEntry() {
        JwtService.Issued portal = service.issueChallenge("u1", JwtService.Purpose.VERIFY, 3, null, "portal",
            Duration.ofMinutes(5));
        assertThat(service.verifyChallenge(portal.token(), JwtService.Purpose.VERIFY))
            .isEqualTo(new JwtService.Challenge("u1", JwtService.Purpose.VERIFY, 3, null, "portal"));
        // A challenge without an entry leads into the administration.
        JwtService.Issued plain = service.issueChallenge("u1", JwtService.Purpose.ENROLL, 4, Duration.ofMinutes(5));
        assertThat(service.verifyChallenge(plain.token(), JwtService.Purpose.ENROLL).entry()).isEqualTo("admin");
        // A challenge is no access token, whatever entry it names.
        assertThatThrownBy(() -> service.verify(portal.token())).isInstanceOf(JwtService.InvalidTokenException.class)
            .hasMessageContaining("type");
    }

    @Test
    void tokensExpireByTheApplicationClock() {
        String token = service.issue(alice).token();

        clock.set(T0.plus(Duration.ofMinutes(15)).minusSeconds(1));
        assertThat(service.verify(token).actorId()).isEqualTo("alice");
        clock.set(T0.plus(Duration.ofMinutes(15)));
        assertThatThrownBy(() -> service.verify(token)).isInstanceOf(JwtService.InvalidTokenException.class)
            .hasMessageContaining("Expired");
    }

    @Test
    void forgedAndMalformedTokensAreRefused() throws Exception {
        String token = service.issue(alice).token();
        String[] parts = token.split("\\.");
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 2) + "AA." + parts[2];

        assertThatThrownBy(() -> service.verify(tampered)).isInstanceOf(JwtService.InvalidTokenException.class);
        assertThatThrownBy(() -> new JwtService(OTHER_KEY, Duration.ofMinutes(15), clock).verify(token))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("signature");
        assertThatThrownBy(() -> service.verify("not-a-token")).isInstanceOf(JwtService.InvalidTokenException.class);

        JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(JwtService.ISSUER).subject("root")
            .expirationTime(Date.from(T0.plusSeconds(600))).build();
        String unsigned = new PlainJWT(new PlainHeader(), claims).serialize();
        assertThatThrownBy(() -> service.verify(unsigned)).isInstanceOf(JwtService.InvalidTokenException.class);

        SignedJWT hs512 = new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims);
        hs512.sign(new MACSigner((new String(KEY, StandardCharsets.UTF_8).repeat(2)).getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> service.verify(hs512.serialize())).isInstanceOf(JwtService.InvalidTokenException.class)
            .hasMessageContaining("algorithm");
    }

    @Test
    void tokensOfAnotherIssuerOrWithoutSubjectAreRefused() throws Exception {
        assertThatThrownBy(() -> service.verify(signed(new JWTClaimsSet.Builder().issuer("elsewhere").subject("x")
            .expirationTime(Date.from(T0.plusSeconds(60))).build())))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("issuer");
        assertThatThrownBy(() -> service.verify(signed(new JWTClaimsSet.Builder().issuer(JwtService.ISSUER)
            .expirationTime(Date.from(T0.plusSeconds(60))).build())))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("subject");
        assertThatThrownBy(() -> service.verify(signed(new JWTClaimsSet.Builder().issuer(JwtService.ISSUER)
            .subject("x").build())))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("Expired");
    }

    @Test
    void keysAndLifetimesAreValidated() {
        assertThatThrownBy(() -> new JwtService("short".getBytes(StandardCharsets.UTF_8), Duration.ofMinutes(1), clock))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtService(KEY, Duration.ZERO, clock)).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.ttl()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void accessTokensCarryTheSecondFactorTime() {
        Actor actor = new Actor("u1", null, Set.of(), Set.of("a"), T0.minusSeconds(30));
        assertThat(service.verify(service.issue(actor).token()).mfaAt()).isEqualTo(T0.minusSeconds(30));
        assertThat(service.verify(service.issue(new Actor("u1", null, Set.of(), Set.of())).token()).mfaAt()).isNull();
    }

    @Test
    void challengesAndAccessTokensCannotStandInForEachOther() throws Exception {
        JwtService.Issued challenge = service.issueChallenge("u1", JwtService.Purpose.VERIFY, 7, Duration.ofMinutes(5));
        assertThat(service.verifyChallenge(challenge.token(), JwtService.Purpose.VERIFY))
            .isEqualTo(new JwtService.Challenge("u1", JwtService.Purpose.VERIFY, 7));
        assertThatThrownBy(() -> service.verify(challenge.token()))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> service.verifyChallenge(challenge.token(), JwtService.Purpose.ENROLL))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("purpose");
        String access = service.issue(new Actor("u1", null, Set.of(), Set.of())).token();
        assertThatThrownBy(() -> service.verifyChallenge(access, JwtService.Purpose.VERIFY))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("type");
        // An untyped token is neither.
        String untyped = untyped(new JWTClaimsSet.Builder().issuer(JwtService.ISSUER).subject("x")
            .expirationTime(Date.from(T0.plusSeconds(60))).build());
        assertThatThrownBy(() -> service.verify(untyped)).isInstanceOf(JwtService.InvalidTokenException.class);
        assertThatThrownBy(() -> service.verifyChallenge(untyped, JwtService.Purpose.VERIFY))
            .isInstanceOf(JwtService.InvalidTokenException.class);
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(() -> service.verifyChallenge(challenge.token(), JwtService.Purpose.VERIFY))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("Expired");
    }

    @Test
    void unsubscribeTokensDoNotExpireAndStandInForNothingElse() {
        String token = service.issueUnsubscribe("u1", "shop.news");
        clock.advance(Duration.ofDays(400));
        assertThat(service.verifyUnsubscribe(token)).isEqualTo(new JwtService.Unsubscribe("u1", "shop.news"));
        assertThatThrownBy(() -> service.verify(token))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("type");
        assertThatThrownBy(() -> service.verifyChallenge(token, JwtService.Purpose.VERIFY))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("type");
        String access = service.issue(new Actor("u1", null, Set.of(), Set.of())).token();
        assertThatThrownBy(() -> service.verifyUnsubscribe(access))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("type");
        // Another template in the same signature: tampered.
        String[] parts = token.split("\\.");
        String claims = new String(java.util.Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
            .replace("shop.news", "shop.else");
        String tampered = parts[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + "." + parts[2];
        assertThatThrownBy(() -> service.verifyUnsubscribe(tampered))
            .isInstanceOf(JwtService.InvalidTokenException.class).hasMessageContaining("signature");
        assertThatThrownBy(() -> new JwtService(OTHER_KEY, Duration.ofMinutes(15), clock).verifyUnsubscribe(token))
            .isInstanceOf(JwtService.InvalidTokenException.class);
    }

    private static String untyped(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }

    private static String signed(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256)
            .type(com.nimbusds.jose.JOSEObjectType.JWT).build(), claims);
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }
}
