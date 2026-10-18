package com.jabiz.app.it.security;

import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review L2 verification (decision D48): when every user and the role {@code ADMIN} were deleted during the setup
 * window, startup makes the first administrator again; that one is a first administrator too, not an ordinary
 * administrator - it retires once another one exists, and does not count towards ending the setup window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class ProtectedGrantRebootstrapIT extends SecurityItSupport {

    @Autowired
    @Qualifier("bootstrapAdministrator")
    ApplicationRunner bootstrap;

    /** Deletes every user and the role ADMIN, as an operator could (dataset writes). */
    static void deleteEveryUserAndTheAdministratorRole(SecurityItSupport it) {
        deleteAll(it, SecurityEntities.USER_ROLE_DATASET, "sec_user_role_version", "user_role_id", "true");
        deleteAll(it, SecurityEntities.ROLE_PERMISSION_DATASET, "sec_role_permission_version", "role_permission_id",
            "role_id IN (SELECT role_id FROM sec_role_version WHERE role_code = 'ADMIN')");
        deleteAll(it, SecurityEntities.USER_DATASET, "sec_user_version", "user_id", "true");
        deleteAll(it, SecurityEntities.ROLE_DATASET, "sec_role_version", "role_id", "role_code = 'ADMIN'");
    }

    private static void deleteAll(SecurityItSupport it, String dataset, String table, String key, String where) {
        for (Map<String, Object> row : query("SELECT DISTINCT ON (" + key + ") " + key + "::text AS id, version_no,"
            + " is_deleted FROM " + table + " WHERE " + where + " ORDER BY " + key + ", version_no DESC")) {
            if (Boolean.FALSE.equals(row.get("is_deleted"))) {
                it.post("/api/datasets/" + dataset + "/commit", it.admin(), Map.of("changes", List.of(Map.of(
                    "action", "DELETE", "id", row.get("id"), "version", row.get("version_no"))))).expectStatus().isOk();
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFirstAdministratorMadeAgainRetiresAsTheFirstOneDid() throws Exception {
        deleteEveryUserAndTheAdministratorRole(this);
        bootstrap.run(null);
        List<Map<String, Object>> made = query("SELECT u.user_id::text AS id FROM sec_user_version u JOIN op_process p"
            + " ON p.process_seq_id = u.process_seq_id WHERE p.process_name = 'SEC_BOOTSTRAP_ADMIN' AND u.version_no = 1"
            + " ORDER BY u.row_id");
        assertThat(made).hasSize(2);
        String again = String.valueOf(made.getLast().get("id"));
        signIn("root", "bootstrap password 1");

        // Two other administrators: the window ends, and the first administrator made again retires.
        String admins = createRole(unique("ADMINS"));
        for (String name : List.of("a", "b")) {
            assign(createUser(unique(name), "password-123"), admins, null);
        }
        insert(SecurityEntities.ROLE_PERMISSION_DATASET, Map.of("roleId", admins, "permission", "*"), null);
        createRole(unique("PLAIN"), "price.read");
        assertThat(query("SELECT 1 FROM sec_protected_grants_armed")).hasSize(1);
        Map<String, Object> problem = post("/api/processes/SEC_USER_ROLE_ASSIGN/latest",
            TestTokens.bearer(tokens, again, "*"), Map.of("userId", createUser(unique("c"), "password-123"),
                "roleId", admins, "reason", "third")).expectStatus().isForbidden().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(String.valueOf(problem)).contains("BOOTSTRAP_ADMIN_RETIRED");
        assertThat(query("SELECT user_id::text AS id FROM sec_bootstrap_retired")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("id", again));
    }
}
