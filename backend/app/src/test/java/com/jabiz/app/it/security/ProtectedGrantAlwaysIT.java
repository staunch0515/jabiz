package com.jabiz.app.it.security;

import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jabiz.security.protected-grants.always=true} (decision D48 item 1, review M1): there is no setup window, yet
 * the platform still makes the first administrator on an empty database (its own grant, valid there only); every
 * protected grant after it waits for an approval.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.protected-grants.always=true",
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class ProtectedGrantAlwaysIT extends SecurityItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void theFirstAdministratorIsMadeAndEveryGrantAfterItWaits() {
        Map<String, Object> session = signIn("root", "bootstrap password 1");
        assertThat(session).containsEntry("permissions", java.util.List.of("*"));
        String root = String.valueOf(query("SELECT user_id::text AS id FROM sec_user_version WHERE user_name = 'root'")
            .getFirst().get("id"));
        String adminRole = String.valueOf(query("SELECT role_id::text AS id FROM sec_role_version WHERE role_code ="
            + " 'ADMIN'").getFirst().get("id"));
        String user = createUser(unique("next"), "password-123");
        Map<String, Object> asked = (Map<String, Object>) post("/api/processes/SEC_USER_ROLE_ASSIGN/latest",
            TestTokens.bearer(tokens, root, "*"), Map.of("userId", user, "roleId", adminRole, "reason", "second"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        assertThat(asked).containsEntry("status", "PENDING");
        assertThat(query("SELECT 1 FROM sec_user_role_version WHERE user_id = ?::uuid", user)).isEmpty();
        // The platform's grant is no way past the check for anyone else: a second bootstrap does nothing.
        assertThat(query("SELECT DISTINCT user_id FROM sec_user_version WHERE user_name = 'root'")).hasSize(1);
    }
}
