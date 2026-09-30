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
        assertThat(config.actorResolver(dev, java.time.Clock.systemUTC(), false).resolve(request)).isNull();
    }

    @Test
    void devHeadersWorkInTheDevProfile() {
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(config.actorResolver(dev, java.time.Clock.systemUTC(), true).resolve(request).actorId()).isEqualTo("admin");
    }

    @Test
    void enablingDevHeadersOutsideTheDevProfileFailsStartup() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> config.actorResolver(prod, java.time.Clock.systemUTC(), true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("only allowed with the 'dev' profile");
        assertThatThrownBy(() -> config.actorResolver(new MockEnvironment(), java.time.Clock.systemUTC(), true))
            .isInstanceOf(IllegalStateException.class);
    }
}
