package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A system that says it is production while {@code qb.reward.default-methods} has never been published (F-F10a review,
 * F-F10b follow-up): the parameter not there does not fall back to the platform's Kudos as it does on a system that has
 * not been set up; the sponsor console's template (asked as the pages ask on such a system) lists no {@code PLATFORM}
 * for a sponsor with no setting of their own, and a save that names no method is refused, even with the Kudos reward
 * switch published true (so it is the default's absence that does it, not the switch). Its own class: the parameter
 * stays unpublished for every test in it.
 */
class KudosRewardBanNoDefaultsIT extends KudosRewardBanProductionSupport {

    @Override
    Set<String> unpublished() {
        return Set.of(QbParams.REWARD_DEFAULT_METHODS);
    }

    @Override
    boolean platformDefault() {
        return false;
    }

    @Test
    void theDefaultMethodsNotPublishedGiveNoKudosOnAProductionSystem() {
        assertThat(query("SELECT 1 FROM sys_param_version WHERE param_key = ?", QbParams.REWARD_DEFAULT_METHODS))
            .isEmpty();
        // The Kudos switch open, so that the default methods alone decide.
        setControlledParam(QbParams.REWARD_KUDOS_ENABLED, "true");
        String sponsor = newSponsor("s-ff10a-nodefault");
        String token = sponsorToken(sponsor);

        // Asked as a production system's pages ask: nothing. Asked as a system not yet set up would be: the platform's.
        assertThat(consoleMethods(token, true)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "").containsEntry("own", false));
        assertThat(consoleMethods(token, false)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "PLATFORM"));

        // The process decides for itself, from the server's own environment: no method named means the platform's,
        // which this sponsor does not have, and it is the ordinary refusal (the switch is open).
        String versionId = version(token, "No default quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "No default", now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

        // An administrator's own setting for the sponsor is what opens it, default or not.
        run(RewardMethodProcesses.SET, TestTokens.bearer(tokens, "ff10a-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ),
            body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM), "note", "F-F10a test"));
        assertThat(consoleMethods(token, true)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "PLATFORM").containsEntry("own", true));
        assertThat(run("QB_PUBLICATION_SAVE", token, save).get("problems")).isEqualTo(List.of());
    }
}
