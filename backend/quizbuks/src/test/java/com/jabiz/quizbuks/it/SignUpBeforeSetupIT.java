package com.jabiz.quizbuks.it;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Before {@code QB_SETUP} has run there are no QuizBuks roles, so neither entry can grant its registration role:
 * registering is refused (422 {@code SIGN_UP_DISABLED}) rather than making users without a role; once set up, it is
 * accepted. A class of its own: it needs a new installation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
    "jabiz.security.entries.app.self-registration=true", "jabiz.security.entries.sponsor.self-registration=true"})
class SignUpBeforeSetupIT extends AccountItSupport {

    @Override
    boolean installs() {
        return false;
    }

    private int register(String entry, String email) {
        return client.post().uri("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
            .bodyValue(Map.of("entry", entry, "email", email, "password", PASSWORD)).exchange()
            .returnResult(String.class).getStatus().value();
    }

    @Test
    void registrationWaitsForSetup() {
        assertThat(register("app", "early@example.com")).isEqualTo(422);
        assertThat(register("sponsor", "early@example.com")).isEqualTo(422);
        assertThat(query("SELECT 1 FROM sec_user_version WHERE lower(email) = 'early@example.com'")).isEmpty();
        setup();
        assertThat(register("app", "early@example.com")).isEqualTo(202);
    }
}
