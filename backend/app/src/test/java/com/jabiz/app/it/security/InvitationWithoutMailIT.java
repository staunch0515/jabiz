package com.jabiz.app.it.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Without mail ({@code jabiz.mail.enabled} off, the tests' default) an invitation would never arrive, so it is refused
 * rather than recorded (decision D63): the administrator learns why at once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class InvitationWithoutMailIT extends SecurityItSupport {

    @Test
    @SuppressWarnings("unchecked")
    void anInvitationIsRefusedWhileMailIsOff() {
        String name = unique("nomail");
        Map<String, Object> created = post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", name,
            "email", name + "@example.com")).expectStatus().isOk().expectBody(MAP).returnResult().getResponseBody();
        String user = (String) ((Map<String, Object>) created.get("output")).get("userId");
        assertThat(ruleCode(post("/api/processes/SEC_USER_INVITE/latest", admin(), Map.of("userId", user))
            .expectStatus().isEqualTo(422).expectBody(MAP).returnResult().getResponseBody())).isEqualTo("MAIL_DISABLED");

        String invited = unique("nomail");
        assertThat(ruleCode(post("/api/processes/SEC_USER_CREATE/latest", admin(), Map.of("userName", invited,
            "email", invited + "@example.com", "invite", true)).expectStatus().isEqualTo(422).expectBody(MAP)
            .returnResult().getResponseBody())).isEqualTo("MAIL_DISABLED");
        assertThat(query("SELECT 1 FROM sec_user_version WHERE user_name = ?", invited)).isEmpty();
    }
}
