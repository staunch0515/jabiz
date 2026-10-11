package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.identity.AccountCodes;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.security.SecurityEntities;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who counts as an administrator, who is never banned, is whoever the administration's entry accepts
 * ({@code jabiz.security.entries.admin.accepted-roles}, review S4): a role added there is protected, a QuizBuks
 * administrator's role left out of it is not. A class of its own: it configures the entry.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.entries.admin.accepted-roles=QB_ADMIN_SUPER,IT_AUDITOR")
class BanAdminRolesIT extends AccountItSupport {

    @Test
    void administratorsAreTheRolesTheAdministrationAccepts() {
        commit(SecurityEntities.ROLE_DATASET, "INSERT", null, null, Map.of("roleCode", "IT_AUDITOR", "labels",
            Map.of("en", "Auditor"), "enabled", true), null);
        String boss = userWithRole(unique("boss"), QbRoles.ADMIN_SUPER);
        String token = tokenOf(boss, QbRoles.ADMIN_SUPER);

        String auditor = userWithRole(unique("aud"), "IT_AUDITOR");
        assertThat(codes(refused("QB_USER_BAN", token, body("userId", auditor, "reason", "No"), 422)))
            .containsExactly(AccountCodes.BAN_ADMIN);
        String other = userWithRole(unique("sup"), QbRoles.ADMIN_SUPER);
        assertThat(codes(refused("QB_USER_BAN", token, body("userId", other, "reason", "No"), 422)))
            .containsExactly(AccountCodes.BAN_ADMIN);
        // Not accepted by the administration here: an ordinary user as far as bans go.
        String content = userWithRole(unique("cnt"), QbRoles.ADMIN_CONTENT);
        assertThat(run("QB_USER_BAN", token, body("userId", content, "reason", "Leaver"))).containsEntry("banned",
            true);
    }
}
