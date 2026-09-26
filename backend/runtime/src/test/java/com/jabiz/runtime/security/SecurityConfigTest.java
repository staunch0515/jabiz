package com.jabiz.runtime.security;

import com.jabiz.runtime.context.ActorAuthentication;
import com.jabiz.runtime.context.ActorResolver;
import com.jabiz.runtime.context.DevHeaderActorResolver;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;

import java.util.Base64;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigTest {

    @Test
    void theSigningKeyIsRequiredOutsideDevelopment() {
        assertThatThrownBy(() -> SecurityConfig.secret(null, false)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("JABIZ_JWT_SECRET");
        assertThatThrownBy(() -> SecurityConfig.secret(" ", false)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> SecurityConfig.secret("not base64!", false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("Base64");
        assertThatThrownBy(() -> SecurityConfig.secret(Base64.getEncoder().encodeToString(new byte[16]), false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");

        byte[] key = new byte[40];
        assertThat(SecurityConfig.secret(Base64.getEncoder().encodeToString(key), false)).hasSize(40);
        // Development alone may run with a made-up key.
        assertThat(SecurityConfig.secret("", true)).hasSize(JwtService.MIN_SECRET_BYTES);
    }

    private static Authentication convert(ActorResolver devActors, MockServerHttpRequest.BaseBuilder<?> request) {
        return SecurityConfig.converter(devActors).convert(MockServerWebExchange.from(request)).block();
    }

    @Test
    void bearerTokensAreHandedOnForVerification() {
        Authentication token = convert(ActorResolver.NONE,
            MockServerHttpRequest.get("/api/x").header("Authorization", "Bearer abc.def.ghi"));

        assertThat(token).isInstanceOf(SecurityConfig.BearerToken.class);
        assertThat(((SecurityConfig.BearerToken) token).token()).isEqualTo("abc.def.ghi");
        assertThat(token.isAuthenticated()).isFalse();
        assertThat(token.toString()).doesNotContain("abc");
    }

    @Test
    void otherSchemesAndEmptyTokensAreRefused() {
        assertThatThrownBy(() -> convert(ActorResolver.NONE,
            MockServerHttpRequest.get("/").header("Authorization", "Basic YWRtaW46YWRtaW4=")))
            .isInstanceOf(BadCredentialsException.class);
        assertThatThrownBy(() -> convert(ActorResolver.NONE,
            MockServerHttpRequest.get("/").header("Authorization", "Bearer ")))
            .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void withoutATokenDevelopmentHeadersNameTheActorOnlyWhenEnabled() {
        MockServerHttpRequest.BaseBuilder<?> request = MockServerHttpRequest.get("/")
            .header("X-Jabiz-Actor", "alice").header("X-Jabiz-Permissions", "order.read");

        assertThat(convert(ActorResolver.NONE, request)).isNull();
        Authentication dev = convert(new DevHeaderActorResolver(), request);
        assertThat(dev).isInstanceOf(ActorAuthentication.class);
        assertThat(((ActorAuthentication) dev).actor().permissions()).isEqualTo(Set.of("order.read"));
        assertThat(dev.isAuthenticated()).isTrue();
        assertThat(dev.getCredentials()).isNull();

        // A token wins over development headers.
        assertThat(convert(new DevHeaderActorResolver(), MockServerHttpRequest.get("/")
            .header("X-Jabiz-Actor", "alice").header("Authorization", "Bearer t")))
            .isInstanceOf(SecurityConfig.BearerToken.class);
        assertThat(convert(new DevHeaderActorResolver(), MockServerHttpRequest.get("/"))).isNull();
    }

    @Test
    void malformedDevelopmentHeadersAreRefused() {
        assertThatThrownBy(() -> convert(new DevHeaderActorResolver(),
            MockServerHttpRequest.get("/").header("X-Jabiz-Actor", "evil actor")))
            .isInstanceOf(BadCredentialsException.class);
    }
}
