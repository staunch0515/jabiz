package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A system that says it is production, installed as an operator does it (F-F10a): {@code QB_SETUP} run and its proposals
 * published, except those of the parameters a subclass names in {@link #unpublished()}: such a parameter "has never been
 * published", the state the default-deny rules are for.
 */
@TestPropertySource(properties = "jabiz.web.environment=production")
abstract class KudosRewardBanProductionSupport extends TreeItSupport {

    /** The parameters whose setup proposal is left unpublished. */
    abstract Set<String> unpublished();

    /**
     * Whether the platform's Kudos are made the default method once installed, as the other tests do (a second
     * publication of {@code qb.reward.default-methods}); a class that leaves that parameter unpublished says no.
     */
    boolean platformDefault() {
        return true;
    }

    @Override
    boolean installs() {
        return false;
    }

    /**
     * The install of the other tests, except for the unpublished proposals; before the players of {@link PlayItSupport}
     * are made (they need the install).
     */
    @Override
    @BeforeEach
    void players() {
        installUnpublished();
        super.players();
    }

    @SuppressWarnings("unchecked")
    private void installUnpublished() {
        if (!query("SELECT 1 FROM qb_setup_record_version LIMIT 1").isEmpty()) {
            return;
        }
        Map<String, Object> output = setup();
        List<String> keys = (List<String>) output.get("paramsProposed");
        List<String> proposals = (List<String>) output.get("proposals");
        for (int i = 0; i < proposals.size(); i++) {
            if (i < keys.size() && unpublished().contains(keys.get(i))) {
                continue;
            }
            client.post().uri("/api/processes/CONTROL_CHANGE_PUBLISH/latest").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, TestTokens.bearer(tokens, "publisher", "control.publish"))
                .bodyValue(Map.of("changeId", proposals.get(i))).exchange().expectStatus().isOk();
        }
        if (platformDefault()) {
            platformRewardMethods();
        }
        installDefaultOperator();
        onboard(SPONSOR_A);
        onboard(SPONSOR_B);
    }

    /** The sponsor console's reward methods, asked as the pages ask on a system of this environment (or not). */
    List<Map<String, Object>> consoleMethods(String token, boolean firstLaunch) {
        return items("qb.sponsor.reward-methods", token,
            Map.of("params", Map.of("firstLaunch", firstLaunch), "limit", 5));
    }
}
