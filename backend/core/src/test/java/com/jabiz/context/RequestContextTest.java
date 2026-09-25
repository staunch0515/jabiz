package com.jabiz.context;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestContextTest {

    @Test
    void rolesAndPermissionsAreCopiedAndImmutable() {
        Set<String> permissions = new HashSet<>(Set.of("order.read"));
        RequestContext ctx = new RequestContext("alice", "t1", Locale.JAPANESE, "r-1", null, permissions);
        permissions.add("order.write");

        assertThat(ctx.roles()).isEmpty();
        assertThat(ctx.permissions()).containsExactly("order.read");
        assertThat(ctx.hasPermission("order.read")).isTrue();
        assertThat(ctx.hasPermission("order.write")).isFalse();
        assertThatThrownBy(() -> ctx.permissions().add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void systemContextHasNoPrivileges() {
        RequestContext ctx = RequestContext.system(Locale.ENGLISH, "job-1");

        assertThat(ctx.actorId()).isEqualTo(RequestContext.SYSTEM_ACTOR);
        assertThat(ctx.tenantId()).isNull();
        assertThat(ctx.roles()).isEmpty();
        assertThat(ctx.permissions()).isEmpty();
    }

    @Test
    void actorLocaleAndRequestIdAreMandatory() {
        assertThatThrownBy(() -> new RequestContext(" ", null, Locale.ENGLISH, "r", null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("actorId");
        assertThatThrownBy(() -> new RequestContext("a", null, null, "r", null, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RequestContext("a", null, Locale.ENGLISH, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requestId");
    }
}
