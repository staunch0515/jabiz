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
@org.junit.jupiter.api.TestMethodOrder(org.junit.jupiter.api.MethodOrderer.MethodName.class)
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

    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("protectedGrantsOffRecorder")
    org.springframework.boot.ApplicationRunner offRecorder;

    /** Review L2: switching them off after the setup window has ended is recorded (and logged) at startup. */
    @Test
    void switchingThemOffAfterTheWindowEndedIsRecorded() throws Exception {
        assertThat(query("SELECT 1 FROM op_process WHERE process_name = 'SEC_PROTECTED_GRANTS_OFF'")).isEmpty();
        execute("INSERT INTO sec_protected_grants_armed (armed_id, armed_time, process_seq_id)"
            + " SELECT 1, now(), max(process_seq_id) FROM op_process");
        offRecorder.run(null);
        assertThat(query("SELECT actor_id FROM op_process WHERE process_name = 'SEC_PROTECTED_GRANTS_OFF'"))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("actor_id", "system"));
    }
}
