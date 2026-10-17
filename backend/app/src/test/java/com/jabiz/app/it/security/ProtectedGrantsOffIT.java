package com.jabiz.app.it.security;

import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code jabiz.security.protected-grants.enabled=false} (decision D48 item 1): protected grants take effect at once
 * however many administrators exist, as before 16r; nothing is recorded as the end of the setup window.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = "jabiz.security.protected-grants.enabled=false")
class ProtectedGrantsOffIT extends SecurityItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void protectedGrantsTakeEffectAtOnce() {
        String admins = createRole(unique("ADMINS"), "*");
        for (int i = 0; i < 3; i++) {
            assign(createUser(unique("a"), "password-123"), admins, null);
        }
        String user = createUser(unique("clerk"), "password-123");
        String poster = createRole(unique("POSTER"), "ledger.post");
        assign(user, poster, null);
        Map<String, Object> output = (Map<String, Object>) post("/api/processes/SEC_USER_SET_PASSWORD/latest",
            TestTokens.bearer(tokens, "it-admin", "*"), Map.of("userId", user, "password", "another-pass-1"))
            .expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody().get("output");
        assertThat(output).containsEntry("status", "APPLIED");
        assertThat(query("SELECT 1 FROM sec_protected_grants_armed")).isEmpty();
    }
}
