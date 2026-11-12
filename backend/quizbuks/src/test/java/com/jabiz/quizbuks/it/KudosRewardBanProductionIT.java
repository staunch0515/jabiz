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
 * A system that says it is production is default-deny for the Kudos reward (F-F10a): while {@code qb.reward.kudos.enabled}
 * has never been published, no publication can offer {@code PLATFORM}, whoever gave it to the sponsor, and the
 * default methods' fallback to the platform's Kudos (the parameter not there) is blocked too; the sponsor console's
 * template, asked as the pages ask it on such a system, offers the same. Once published true, Kudos are open.
 */
class KudosRewardBanProductionIT extends KudosRewardBanProductionSupport {

    @Override
    Set<String> unpublished() {
        return Set.of(QbParams.REWARD_KUDOS_ENABLED);
    }

    @Test
    void theSwitchNotPublishedCountsAsOffUntilItIsPublishedTrue() {
        assertThat(query("SELECT 1 FROM sys_param_version WHERE param_key = ?", QbParams.REWARD_KUDOS_ENABLED))
            .isEmpty();
        String sponsor = newSponsor("s-ff10a-prod");
        String token = sponsorToken(sponsor);
        run(RewardMethodProcesses.SET, TestTokens.bearer(tokens, "ff10a-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ),
            body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH),
                "note", "F-F10a test"));
        String versionId = version(token, "Production quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Kudos in production", now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));

        // The default method (none named) is the platform's, and the sponsor's setting gives it too: refused.
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
        save.put("rewardMethod", RewardMethod.PLATFORM);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
        // The other methods are as before.
        save.put("rewardMethod", RewardMethod.SPONSOR_CASH);
        assertThat(run("QB_PUBLICATION_SAVE", token, save).get("problems")).isEqualTo(List.of());

        // The console, asked as the pages ask on this system, offers no Kudos; asked as a test system, it does.
        assertThat(consoleMethods(token, true)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "SPONSOR_CASH"));
        assertThat(consoleMethods(token, false)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "PLATFORM,SPONSOR_CASH"));

        // A sponsor with no setting of their own is offered no Kudos either (the default is the platform's, the switch
        // is not there).
        assertThat(consoleMethods(sponsorToken(newSponsor("s-ff10a-prod-default")), true)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "").containsEntry("own", false));

        // The setup's proposal published (false, the first launch's value): off for the parameter's own sake now.
        Object waiting = query("SELECT change_id FROM (SELECT DISTINCT ON (change_id) * FROM sys_control_change_version "
            + "ORDER BY change_id, version_no DESC) c WHERE status = 'PROPOSED' AND change_values LIKE ?",
            "%\"" + QbParams.REWARD_KUDOS_ENABLED + "\"%").getFirst().get("change_id");
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", String.valueOf(waiting)));
        assertThat(consoleMethods(token, false)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "SPONSOR_CASH"));
        save.put("rewardMethod", RewardMethod.PLATFORM);
        assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
            .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);

        // Published true: open.
        setControlledParam(QbParams.REWARD_KUDOS_ENABLED, "true");
        assertThat(consoleMethods(token, true)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("methods", "PLATFORM,SPONSOR_CASH"));
        save.put("rewardMethod", RewardMethod.PLATFORM);
        assertThat(run("QB_PUBLICATION_SAVE", token, save).get("problems")).isEqualTo(List.of());
    }
}
