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
 * at saving (the referral reward too), copying, submitting and in the package import's publication step, with
 * 422 {@code QB_REWARD_KUDOS_DISABLED} (any other method that is not allowed keeps {@code QB_REWARD_METHOD_NOT_ALLOWED}
 * and its message); the other methods are untouched, a publication already live or in review goes on, a challenge
 * pays nothing and is not asked, and the sponsor console's template offers the same methods as the processes accept.
 * The system that says it is production is default-deny: {@code KudosRewardBanProductionIT}.
 */
class KudosRewardBanIT extends TreeItSupport {

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
        setControlledParam(QbParams.REWARD_KUDOS_ENABLED, String.valueOf(on));
    }

    private static List<Object> problemCodes(Map<String, Object> saved) {
        return ((List<?>) saved.get("problems")).stream().map(p -> (Object) ((Map<?, ?>) p).get("code")).toList();
    }

    private List<Map<String, Object>> consoleMethods(String token) {
        return items("qb.sponsor.reward-methods", token, Map.of("params", Map.of(), "limit", 5));
    }

    @Test
    void theSwitchIsDeclaredOnSoNothingChangesUntilItIsTurnedOff() {
        // The first version is the setup's proposal as published, in the order the table keeps them.
        assertThat(query("SELECT param_value FROM sys_param_version WHERE param_key = ? ORDER BY version_no",
            QbParams.REWARD_KUDOS_ENABLED)).extracting(row -> row.get("param_value")).first().isEqualTo("true");
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
                .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
            save.put("rewardMethod", RewardMethod.PLATFORM);
            assertThat(codes(refused("QB_PUBLICATION_SAVE", token, save, 422)))
                .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);

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
                .contains(PublishingCodes.REWARD_KUDOS_DISABLED);
            save.remove("referral");

            // The other methods are untouched.
            Map<String, Object> cash = run("QB_PUBLICATION_SAVE", token, save);
            assertThat(problemCodes(cash)).doesNotContain(PublishingCodes.REWARD_METHOD_NOT_ALLOWED,
                PublishingCodes.REWARD_KUDOS_DISABLED);

            // Copying a publication paid by the platform says so as a problem of the copy.
            assertThat(problemCodes(run("QB_PUBLICATION_COPY", token, body("publicationId", live))))
                .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);

            // Submitting a draft saved while the switch was on is refused.
            assertThat(codes(refused("QB_PUBLICATION_SUBMIT", token, body("publicationId", draft), 422)))
                .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);

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
                .containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);

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

    @Test
    void anotherMethodThatIsNotAllowedKeepsItsCodeAndItsMessageWhateverTheSwitchSays() {
        String sponsor = newSponsor("s-ff10a-other");
        String token = sponsorToken(sponsor);
        allow(sponsor, RewardMethod.PLATFORM, RewardMethod.SPONSOR_CASH);
        String versionId = version(token, "Other quiz");
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, "Offline", now.plus(Duration.ofDays(1)), now.plus(Duration.ofDays(8)));
        save.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);

        // The switch on: the prize at the sponsor's place is not theirs to use.
        Map<String, Object> on = refused("QB_PUBLICATION_SAVE", token, save, 422);
        assertThat(codes(on)).containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
        String original = String.valueOf(violations(on).getFirst().get("message"));
        assertThat(original).doesNotContain("until it is opened");

        kudosRewards(false);
        try {
            // The switch off: the same refusal, word for word, without the sentence about Kudos.
            Map<String, Object> off = refused("QB_PUBLICATION_SAVE", token, save, 422);
            assertThat(codes(off)).containsExactly(PublishingCodes.REWARD_METHOD_NOT_ALLOWED);
            assertThat(String.valueOf(violations(off).getFirst().get("message")))
                .doesNotContain("until it is opened").contains("Allowed: SPONSOR_CASH");
            // Kudos paid by the platform has its own refusal and message (the brand is the product's, not a token).
            save.put("rewardMethod", RewardMethod.PLATFORM);
            Map<String, Object> kudos = refused("QB_PUBLICATION_SAVE", token, save, 422);
            assertThat(codes(kudos)).containsExactly(PublishingCodes.REWARD_KUDOS_DISABLED);
            assertThat(String.valueOf(violations(kudos).getFirst().get("message")))
                .contains("cannot be used to publish until it is opened").doesNotContain("@QB_BRAND_NAME@");
        } finally {
            kudosRewards(true);
        }
    }

    @Test
    void aPublicationInReviewIsApprovedAfterTheSwitchIsOffAndGoesLiveAsSubmitted() {
        // The plan (section 1.4, b): what was submitted, in review or live, is not broken by a later switch. The
        // method is checked when a draft is saved and submitted, not when a reviewer decides.
        String sponsor = newSponsor("s-ff10a-review");
        String token = sponsorToken(sponsor);
        allow(sponsor, RewardMethod.PLATFORM);
        String versionId = version(token, "Review quiz");
        Instant now = clock.instant();
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, complete(versionId, "In review", now,
            now.plus(Duration.ofDays(7)))).get("publicationId");
        autoApprove(false);
        try {
            Map<String, Object> submitted = submit(token, publicationId);
            assertThat(submitted).containsEntry("status", "PENDING_REVIEW");
            kudosRewards(false);
            try {
                decide((String) submitted.get("approvalRequestId"), "APPROVE", null);
                assertThat(status(publicationId)).isIn("LIVE", "SCHEDULED");
                assertThat(row(publicationId)).containsEntry("reward_method", RewardMethod.PLATFORM);
            } finally {
                kudosRewards(true);
            }
        } finally {
            autoApprove(true);
        }
    }

    @Test
    void aChallengePaysNothingAndIsNotAskedForTheRewardMethod() {
        Copy copy = copy();
        String direct = sponsorToken(copy.operator().leadId());
        kudosRewards(false);
        try {
            Map<String, Object> saved = run("QB_PUBLICATION_SAVE", direct, challenge(direct, copy.treeId(),
                copy.start(), "Challenge while Kudos are closed"));
            assertThat(problemCodes(saved)).isEmpty();
            assertThat(submit(direct, (String) saved.get("publicationId")).get("status")).isIn("LIVE", "SCHEDULED");
        } finally {
            kudosRewards(true);
        }
    }

    @Test
    void theSwitchTouchesOnlyThePlatformsKudosAndNeverAChannelsMethod() {
        // A channel's publication has the method CHANNEL, which no sponsor may be allowed (it is not in ALL) and which
        // never goes through the sponsor's methods; ShopifyStorefrontIT publishes a campaign with the switch off.
        assertThat(RewardMethod.ALL).doesNotContain(RewardMethod.CHANNEL);
        assertThat(RewardMethod.withKudosSwitch(List.of(RewardMethod.CHANNEL, RewardMethod.PLATFORM), null, true))
            .containsExactly(RewardMethod.CHANNEL);
    }
}
