package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an administrator removed after {@code QB_SETUP} stays removed when it runs again: a revoked permission, a
 * deleted role and its grants, a changed parameter (docs/quizbuks/plans/Q1-skeleton.md, review item 1).
 */
class SetupRemovalsIT extends QbItSupport {

    @Test
    void runningAgainNeverAddsBackWhatWasRemoved() {
        setup();
        String content = roleId(QbRoles.ADMIN_CONTENT);
        String taker = roleId(QbRoles.TAKER);

        // The content administrators no longer see tasks.
        revoke(content, "task.read");
        // Quiz takers are dropped as a role: first its grants, then the role.
        for (String permission : QbRoles.of(QbRoles.TAKER).permissions()) {
            revoke(taker, permission);
        }
        Map<String, Object> role = latest("sec_role_version", "role_id", "role_id = '" + taker + "'").getFirst();
        commit(SecurityEntities.ROLE_DATASET, "DELETE", taker, ((Number) role.get("version_no")).longValue(), null,
            null);
        // The threshold is changed; a parameter is only ever declared once.
        client.post().uri("/api/processes/PARAM_SET/latest").header("Authorization", admin())
            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("key", QbParams.TRANSFER_THRESHOLD, "value", "3000")).exchange().expectStatus().isOk();

        Map<String, Object> again = setup();
        assertThat(again).containsEntry("rolesCreated", List.of()).containsEntry("permissionsAdded", 0)
            .containsEntry("accountsOpened", List.of()).containsEntry("countriesAdded", 0)
            .containsEntry("paramsCreated", List.of());

        assertThat(latest("sec_role_version", "role_id", "role_code = 'QB_TAKER' AND NOT is_deleted")).isEmpty();
        assertThat(latest("sec_role_permission_version", "role_permission_id",
            "role_id = '" + content + "' AND permission = 'task.read' AND NOT is_deleted")).isEmpty();
        assertThat(latest("sec_role_permission_version", "role_permission_id",
            "role_id = '" + taker + "' AND NOT is_deleted")).isEmpty();
        assertThat(latest("sys_param_version", "param_id", "param_key = 'qb.transfer.threshold'")).singleElement()
            .satisfies(param -> assertThat(param.get("param_value")).isEqualTo("3000"));
    }

    private String roleId(String code) {
        return String.valueOf(query("SELECT role_id FROM sec_role_version WHERE role_code = ?", code).getFirst()
            .get("role_id"));
    }

    private void revoke(String roleId, String permission) {
        Map<String, Object> grant = latest("sec_role_permission_version", "role_permission_id",
            "role_id = '" + roleId + "' AND permission = '" + permission + "'").getFirst();
        commit(SecurityEntities.ROLE_PERMISSION_DATASET, "DELETE", String.valueOf(grant.get("role_permission_id")),
            ((Number) grant.get("version_no")).longValue(), null, null);
    }
}
