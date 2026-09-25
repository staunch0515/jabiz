package com.jabiz.runtime.context;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextConfigTest {

    private final RequestContextConfig config = new RequestContextConfig();
    private final MockServerHttpRequest request =
        MockServerHttpRequest.get("/").header("X-Jabiz-Actor", "admin").build();

    @Test
    void devHeadersAreIgnoredUnlessEnabled() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(config.actorResolver(dev, false).resolve(request)).isEqualTo(Actor.ANONYMOUS);
    }

    @Test
    void devHeadersWorkInTheDevProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(config.actorResolver(dev, true).resolve(request).actorId()).isEqualTo("admin");
    }

    @Test
    void enablingDevHeadersOutsideTheDevProfileFailsStartup() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> config.actorResolver(prod, true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only allowed with the 'dev' profile");
        assertThatThrownBy(() -> config.actorResolver(new MockEnvironment(), true))
            .isInstanceOf(IllegalStateException.class);
    }
}
