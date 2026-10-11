package com.jabiz.runtime.context;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevHeaderActorResolverTest {

    private final DevHeaderActorResolver resolver =
        new DevHeaderActorResolver(Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void anActorComesFromTheHeaders() {
        Actor actor = resolver.resolve(MockServerHttpRequest.get("/api/x")
            .header(DevHeaderActorResolver.ACTOR_HEADER, "alice")
            .header(DevHeaderActorResolver.PERMISSIONS_HEADER, "a.read,a.write").build());
        assertThat(actor.actorId()).isEqualTo("alice");
        assertThat(actor.permissions()).containsExactlyInAnyOrder("a.read", "a.write");
    }

    /** Not even in development does a request pass for the system or the anonymous visitor (decision D41). */
    @Test
    void theReservedActorsAreRefused() {
        for (String reserved : java.util.List.of("system", "anonymous")) {
            assertThatThrownBy(() -> resolver.resolve(MockServerHttpRequest.get("/api/x")
                .header(DevHeaderActorResolver.ACTOR_HEADER, reserved)
                .header(DevHeaderActorResolver.PERMISSIONS_HEADER, "*").build()))
                .as(reserved).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Reserved");
        }
    }
}
