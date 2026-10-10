package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roles and grants an administrator scheduled to begin later count as existing: {@code QB_SETUP} does not make a
 * second one of them now (review item 5).
 */
class SetupScheduledIT extends QbItSupport {

    @Test
    void scheduledRolesAndGrantsAreNotMadeAgain() {
        Instant later = clock.instant().plus(Duration.ofDays(30));
        // The takers' role is scheduled to begin in a month.
        commit(SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode", QbRoles.TAKER,
            "labels", Map.of("en", "Takers"), "enabled", true), later);
        // The sponsors' role exists already, and it is to be allowed top-ups in a month.
        commit(SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode", QbRoles.SPONSOR,
            "labels", Map.of("en", "Sponsors"), "enabled", true), null);
        String sponsor = String.valueOf(query("SELECT role_id FROM sec_role_version WHERE role_code = ?",
            QbRoles.SPONSOR).getFirst().get("role_id"));
        commit(SecurityEntities.ROLE_PERMISSION_DATASET, "INSERT", null, null, Map.of("roleId", sponsor,
            "permission", QbPermissions.TOPUP), later);

        Map<String, Object> output = setup();

        assertThat(output.get("rolesCreated")).isEqualTo(List.of(QbRoles.ADMIN_CONTENT, QbRoles.ADMIN_FINANCE,
            QbRoles.ADMIN_SUPER));
        int expected = QbRoles.all().stream().mapToInt(role -> role.permissions().size()).sum()
            - QbRoles.of(QbRoles.TAKER).permissions().size() - 1;
        assertThat(output.get("permissionsAdded")).isEqualTo(expected);
        assertThat(query("SELECT 1 FROM sec_role_version WHERE role_code = ?", QbRoles.TAKER)).hasSize(1);
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid AND permission = ?",
            sponsor, QbPermissions.TOPUP)).hasSize(1);
        assertThat(query("SELECT 1 FROM sec_role_permission_version WHERE role_id = ?::uuid", sponsor))
            .hasSize(QbRoles.of(QbRoles.SPONSOR).permissions().size());
    }
}
