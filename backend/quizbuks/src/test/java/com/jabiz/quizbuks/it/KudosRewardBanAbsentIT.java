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
 * Submitting and copying on a system that says it is production while {@code qb.reward.kudos.enabled} has never been
 * published (F-F10a review, F-F10b follow-up): the switch not there counts as off at the two paths that read a
 * publication saved earlier, as it does at saving. A publication paid by the platform's Kudos cannot be saved on such a
 * system, so the fixture is the one a deployment finds: a draft and a live publication written before the switch
 * existed (a new version of the stored row, as an earlier release wrote it). Its own class: the switch stays unpublished
 * for every test in it.
 */
class KudosRewardBanAbsentIT extends KudosRewardBanProductionSupport {

    @Override
    Set<String> unpublished() {
        return Set.of(QbParams.REWARD_KUDOS_ENABLED);
    }

    /** A new version of the publication's row, as a release before the switch would have saved it: paid by the platform. */
    private void paidByThePlatformSinceBefore(String publicationId) {
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'qb_publication_version' AND column_name NOT IN ('row_id', "
            + "'version_no', 'reward_method') ORDER BY ordinal_position").stream()
            .map(row -> (String) row.get("column_name")).toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO qb_publication_version (" + list + ", version_no, reward_method) SELECT " + list
            + ", version_no + 1, 'PLATFORM' FROM qb_publication_version WHERE publication_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", publicationId);
    }

    @Test
    void aPlatformPublicationIsRefusedAtSubmittingAndCopyingWhileTheSwitchHasNeverBeenPublished() {
        assertThat(query("SELECT 1 FROM sys_param_version WHERE param_key = ?", QbParams.REWARD_KUDOS_ENABLED))
            .isEmpty();
        String sponsor = newSponsor("s-ff10a-absent");
        String token = sponsorToken(sponsor);
        run(RewardMethodProcesses.SET, TestTokens.bearer(tokens, "ff10a-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ),
            body("userId", sponsor, "methods", List.of(RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH),
                "note", "F-F10a test"));
        String versionId = version(token, "Absent quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Saved before the switch", now.plus(Duration.ofDays(1)),
            now.plus(Duration.ofDays(8)));
        save.put("rewardMethod", RewardMethod.SPONSOR_CASH);
        String draft = (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
        String live = livePublication(sponsor, "Live before the switch", Map.of("rewardMethod", RewardMethod.SPONSOR_CASH));
        paidByThePlatformSinceBefore(draft);
        paidByThePlatformSinceBefore(live);

        // Submitting the draft: refused with the Kudos code, and it stays a draft.
        assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
            .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
        assertThat(status(draft)).isEqualTo("DRAFT");

        // Copying the live one: the copy is a problem of its own (it is not saved), the original goes on.
        Map<String, Object> copy = run("QB_PUBLICATION_COPY", token, body("publicationId", live));
        assertThat(((List<?>) copy.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList())
            .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
        assertThat(status(live)).isEqualTo("LIVE");
    }
}
