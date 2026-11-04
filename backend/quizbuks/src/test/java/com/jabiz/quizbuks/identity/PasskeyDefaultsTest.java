package com.jabiz.quizbuks.identity;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The passkeys' defaults of a deployment (docs/quizbuks/plans/Q97-passkeys.md, D-Q97-2, D-Q97-3). */
class PasskeyDefaultsTest {

    private static Map<String, String> defaults(Map<String, String> settings) {
        return PasskeyDefaults.defaults(settings::get);
    }

    @Test
    void theRelyingPartyIsTheDeploymentsOwnHost() {
        assertThat(defaults(Map.of("jabiz.mail.base-url", "https://staging.quizbuks.com"))).isEqualTo(
            Map.of("quizbuks.passkeys.rp-id", "staging.quizbuks.com",
                "quizbuks.passkeys.origins", "https://staging.quizbuks.com", "quizbuks.passkeys.default", "true"));
        // A trailing slash, capitals and a port; localhost may be http.
        assertThat(defaults(Map.of("jabiz.mail.base-url", "https://QuizBuks.com/")))
            .containsEntry("quizbuks.passkeys.rp-id", "quizbuks.com")
            .containsEntry("quizbuks.passkeys.origins", "https://quizbuks.com");
        assertThat(defaults(Map.of("jabiz.mail.base-url", "http://localhost:8080")))
            .containsEntry("quizbuks.passkeys.rp-id", "localhost")
            .containsEntry("quizbuks.passkeys.origins", "http://localhost:8080")
            .containsEntry("quizbuks.passkeys.default", "true");
    }

    @Test
    void whatTheDeploymentSetsWins() {
        Map<String, String> given = defaults(Map.of("QB_PASSKEY_RP_ID", "quizbuks.com",
            "QB_PASSKEY_ORIGINS", "https://quizbuks.com,https://www.quizbuks.com",
            "jabiz.mail.base-url", "https://staging.quizbuks.com"));
        assertThat(given).isEqualTo(Map.of("quizbuks.passkeys.default", "true"));
        // Half given: nothing guessed, and the entries stay off (the platform's check names what is missing when
        // QB_PASSKEYS turns them on).
        assertThat(defaults(Map.of("QB_PASSKEY_RP_ID", "quizbuks.com", "jabiz.mail.base-url", "https://x.com")))
            .isEqualTo(Map.of("quizbuks.passkeys.default", "false"));
    }

    @Test
    void withoutAHostToTakePasskeysAreOff() {
        for (String address : new String[] {"", "not an address", "http://quizbuks.com", "https://quizbuks.com/admin",
            "https://user@quizbuks.com", "https://quizbuks.com/?x=1", "ftp://quizbuks.com", "http://127.0.0.1:8080"}) {
            assertThat(defaults(Map.of("jabiz.mail.base-url", address)))
                .as(address).isEqualTo(Map.of("quizbuks.passkeys.default", "false"));
        }
        assertThat(defaults(Map.of())).isEqualTo(Map.of("quizbuks.passkeys.default", "false"));
    }
}
