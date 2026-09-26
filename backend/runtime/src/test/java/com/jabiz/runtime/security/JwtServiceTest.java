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
        assertThat(service.verify(issued.token())).isEqualTo(alice);
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

    private static String signed(JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
        jwt.sign(new MACSigner(KEY));
        return jwt.serialize();
    }
}
