package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The first administrator comes from configuration (docs/design/10-security.md section 7). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.bootstrap-admin.user-name=root",
    "jabiz.security.bootstrap-admin.password=bootstrap password 1"})
class BootstrapAdminIT extends SecurityItSupport {

    @Test
    void theConfiguredAdministratorCanSignInWithEveryPermission() {
        Map<String, Object> session = signIn("root", "bootstrap password 1");

        assertThat(session).containsEntry("roles", List.of("ADMIN")).containsEntry("permissions", List.of("*"));
        post("/api/datasets/urn:jabiz:dataset:platform:SecUser/query", bearerOf(session), Map.of())
            .expectStatus().isOk();
        // Created once: only this user exists.
        assertThat(query("SELECT DISTINCT user_id FROM sec_user_version")).hasSize(1);
    }
}
