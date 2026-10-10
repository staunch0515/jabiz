package com.jabiz.runtime.security;

import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.security.LoginAttemptPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RbacTest {

    private static EntityInstance role(String id, String code, boolean enabled) {
        return new EntityInstance(id, SecurityEntities.ROLE, 1, null,
            Map.of("roleId", id, "roleCode", code, "enabled", enabled));
    }

    private static EntityInstance grant(String roleId, String permission) {
        return new EntityInstance(roleId + permission, SecurityEntities.ROLE_PERMISSION, 1, null,
            Map.of("roleId", roleId, "permission", permission));
    }

    @Test
    void accessIsWhatTheEnabledRolesGrant() {
        Rbac.Access access = Rbac.access(List.of(role("r1", "CLERK", true), role("r2", "OFF", false)),
            List.of(grant("r1", "order.read"), grant("r1", "order.write"), grant("r2", "order.delete")));

        assertThat(access.roles()).isEqualTo(Set.of("CLERK"));
        assertThat(access.permissions()).isEqualTo(Set.of("order.read", "order.write"));
    }

    @Test
    void anEntryCountsOnlyTheRolesItAccepts() {
        // ADMIN requires a second factor and is limited to 2025; CUSTOMER neither (decision D36 item 1).
        EntityInstance admin = new EntityInstance("r1", SecurityEntities.ROLE, 1, null,
            Map.of("roleId", "r1", "roleCode", "ADMIN", "enabled", true, "requireMfa", true));
        EntityInstance customer = role("r2", "CUSTOMER", true);
        Instant from = Instant.parse("2025-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-01-01T00:00:00Z");
        List<EntityInstance> assignments = List.of(
            new EntityInstance("a1", SecurityEntities.USER_ROLE, 1, null,
                Map.of("userId", "u", "roleId", "r1", "dataFrom", from, "dataTo", to)),
            new EntityInstance("a2", SecurityEntities.USER_ROLE, 1, null, Map.of("userId", "u", "roleId", "r2")));
        List<EntityInstance> grants = List.of(grant("r1", "*"), grant("r2", "order.place"));
        SignInEntries.Entry portal = new SignInEntries.Entry("portal", Set.of("CUSTOMER"), false, Set.of(), false,
            "/");
        SignInEntries.Entry everything = new SignInEntries.Entry("admin", Set.of("*"), false, Set.of(), false, "/");

        List<EntityInstance> portalRoles = Rbac.acceptedBy(List.of(admin, customer), portal);
        Rbac.Access inPortal = Rbac.access(assignments, portalRoles, grants);
        assertThat(inPortal.roles()).containsExactly("CUSTOMER");
        assertThat(inPortal.permissions()).containsExactly("order.place");
        assertThat(inPortal.mfaRequired()).isFalse();
        // The only accepted assignment is not limited in time.
        assertThat(inPortal.dataPeriod()).isNull();

        Rbac.Access inAdmin = Rbac.access(assignments, Rbac.acceptedBy(List.of(admin, customer), everything), grants);
        assertThat(inAdmin.roles()).containsExactlyInAnyOrder("ADMIN", "CUSTOMER");
        assertThat(inAdmin.mfaRequired()).isTrue();
        SignInEntries.Entry adminOnly = new SignInEntries.Entry("back", Set.of("ADMIN"), false, Set.of(), false, "/");
        assertThat(Rbac.access(assignments, Rbac.acceptedBy(List.of(admin, customer), adminOnly), grants)
            .dataPeriod()).isEqualTo(com.jabiz.context.DataPeriod.of(from, to));

        assertThat(Rbac.acceptedBy(List.of(admin, customer), null)).isEmpty();
    }

    @Test
    void queriesReadEverythingInAStableOrderAndNeverFromATruncatedList() {
        EntityQuery query = Rbac.assignmentsOf("u1");
        assertThat(query.limit()).isEqualTo(Rbac.MAX_ROWS);
        assertThat(query.sorts()).isNotEmpty();
        assertThat(Rbac.permissionsOf(List.of(role("r1", "A", true), role("r2", "B", false))).predicate())
            .hasToString("In[field=roleId, values=[r1]]");

        List<EntityInstance> full = Collections.nCopies(Rbac.MAX_ROWS, role("r", "R", true));
        assertThatThrownBy(() -> Rbac.access(full, List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> Rbac.rolesOf(full)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void loginRecordsCarryTheirCounters() {
        Instant until = Instant.parse("2026-01-31T09:15:00Z");
        EntityInstance record = new EntityInstance("l1", SecurityEntities.LOGIN_RECORD, 1, null, Map.of(
            "attemptNo", new BigDecimal("7"), "failureCount", new BigDecimal("5"), "lockedUntil", until));

        assertThat(Rbac.state(record)).isEqualTo(new LoginAttemptPolicy.State(7, 5, until));
        assertThat(Rbac.state(null)).isEqualTo(LoginAttemptPolicy.State.INITIAL);
    }

    @Test
    void issuedTokensDoNotPrintThemselves() {
        assertThat(new RefreshTokenStore.Issued("secret-refresh", null, Instant.EPOCH).toString())
            .doesNotContain("secret-refresh");
        assertThat(new JwtService.Issued("secret-access", Instant.EPOCH).toString()).doesNotContain("secret-access");
    }
}
