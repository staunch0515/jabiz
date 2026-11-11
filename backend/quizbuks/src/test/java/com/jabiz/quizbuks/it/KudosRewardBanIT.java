package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.PublishingCodes;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.sponsorpackage.PackageImportProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kudos reward switch {@code qb.reward.kudos.enabled} (F-F10a, docs/quizbuks/plans/impl/F-F10.md section 1.4): off,
 * no publication can offer the reward method {@code PLATFORM} whatever gave it to the sponsor (their own setting here),
 * at saving (the referral reward too), copying, submitting and in the package import's publication step, with the same
 * 422 {@code QB_REWARD_METHOD_NOT_ALLOWED}; the other methods are untouched, a publication already live goes on, and
 * the sponsor console's template offers the same methods as the processes accept.
 */
class KudosRewardBanIT extends PlayItSupport {

    private String methodsAdmin() {
        return TestTokens.bearer(tokens, "ff10a-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
    }

    private void allow(String sponsor, String... methods) {
        run(RewardMethodProcesses.SET, methodsAdmin(), body("userId", sponsor, "methods", List.of(methods),
            "note", "F-F10a test"));
    }

    /** Sets the switch with four eyes. */
    private void kudosRewards(boolean on) {
        Map<String, Object> change = run("CONTROL_CHANGE_PROPOSE", proposer(), body("targetEntity", "SysParam",
            "values", Map.of("paramKey", QbParams.REWARD_KUDOS_ENABLED, "value", String.valueOf(on)),
            "reason", "F-F10a test"));
        run("CONTROL_CHANGE_PUBLISH", financeAdmin(), body("changeId", change.get("changeId")));
    }

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private List<Map<String, Object>> consoleMethods(String token) {
        return items("qb.sponsor.reward-methods", token, Map.of("params", Map.of(), "limit", 5));
    }

    @Test
    void theSwitchIsDeclaredOnSoNothingChangesUntilItIsTurnedOff() {
        assertThat(query("SELECT param_value FROM sys_param_version WHERE param_key = ?",
            QbParams.REWARD_KUDOS_ENABLED)).extracting(row -> row.get("param_value")).containsExactly("true");
    }

    @Test
    void aKudosRewardIsRefusedAtSavingCopyingSubmittingAndImportingWhenTheSwitchIsOff() {
        String sponsor = newSponsor("s-ff10a-ban");
        String token = sponsorToken(sponsor);
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH);
        String versionId = version(token, "Ban quiz");
        Instant now = clock.instant();
        Instant start = now.plus(Duration.ofDays(1));
        Instant end = now.plus(Duration.ofDays(8));

        // With the switch on: a draft and a live publication, both paid by the platform.
        String draft = (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, "Draft before", start, end))
            .get("publicationId");
        String live = livePublication(sponsor, "Live before");
        assertThat(consoleMethods(token)).singleElement().satisfies(row ->
            assertThat(row).containsEntry("methods", "PLATFORM,SPONSOR_CASH"));

        kudosRewards(false);
        try {
            // The console offers what the processes accept.
            assertThat(consoleMethods(token)).singleElement().satisfies(row ->
                assertThat(row).containsEntry("methods", "SPONSOR_CASH").containsEntry("own", true));

            // Saving: the default method (none named) is the platform's, and so is a named one.
            Map<String, Object> save = complete(versionId, "Kudos now", start, end);
            assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
            save.put("rewardMethod", RewardMethod.PLATFORM);
            assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

            // The referral reward is a Kudos reward too.
            save.put("rewardMethod", RewardMethod.SPONSOR_CASH);
            Map<String, Object> referral = new LinkedHashMap<>();
            referral.put("quota", 5);
            referral.put("method", RewardMethod.PLATFORM);
            referral.put("amount", 100);
            referral.put("description", null);
            referral.put("referrerRequires", "NONE");
            referral.put("refereeRules", List.of());
            referral.put("days", 7);
            save.put("referral", referral);
            assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
                .contains(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
            save.remove("referral");

            // The other methods are untouched.
            Map<String, Object> cash = run("QB_PUBLICATION_SAVE", token, save);
            assertThat(problemCodes(cash)).doesNotContain(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

            // Copying a publication paid by the platform says so as a problem of the copy.
            assertThat(problemCodes(run("QB_PUBLICATION_COPY", token, body("publicationId", live))))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

            // Submitting a draft saved while the switch was on is refused.
            assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

            // The package import's publication step is the same save (a sponsor of its own: an account that
            // submitted a publication imports only its profile).
            String importer = newSponsor("s-ff10a-import");
            String importToken = sponsorToken(importer);
            allow(importer, RewardMethod.PLATFORM);
            String importVersion = version(importToken, "Import quiz");
            Object importId = run(PackageImportProcesses.START, importToken, body("packageHash", "f".repeat(64),
                "formatVersion", 2, "mode", "FULL", "batchSize", 2, "stepsTotal", 1)).get("importId");
            Map<String, Object> imported = new LinkedHashMap<>();
            imported.put("importId", importId);
            imported.put("step", "publication:1");
            imported.put("publication", body("save", complete(importVersion, "Imported", start, end),
                "topics", body()));
            assertThat(codes(refused(PackageImportProcesses.STEP, importToken, imported, 422)))
                .containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);

            // What is live goes on as it is.
            assertThat(status(live)).isEqualTo("LIVE");
        } finally {
            kudosRewards(true);
        }

        // Turned on again, the same draft submits.
        assertThat(consoleMethods(token)).singleElement().satisfies(row ->
            assertThat(row).containsEntry("methods", "PLATFORM,SPONSOR_CASH"));
        assertThat(submit(token, draft)).containsEntry("status", "SCHEDULED");
    }
}
