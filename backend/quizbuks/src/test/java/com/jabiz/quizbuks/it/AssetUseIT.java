package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.asset.AssetCodes;
import com.jabiz.quizbuks.asset.AssetProcesses;
import com.jabiz.quizbuks.asset.AssetRules;
import com.jabiz.quizbuks.asset.QbAssets;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.job.JobRunner;
import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * F-C1b (docs/quizbuks/plans/impl/F-C1.md): the content and publication saves record which of the sponsor's assets a
 * place was copied from, in their own transaction: only the sponsor's own assets, of a kind that suits the place; one
 * record per place, replaced when picked again, gone with the place; a changed asset shows its places as older
 * content, and what was copied stays as it is.
 */
class AssetUseIT extends PlayItSupport {

    private static final String USES = "qb.sponsor.asset-uses";

    @Autowired
    @Qualifier("fileSweepJob")
    JobDefinition<FileProcesses.PurgeInput> sweep;

    private String uploadImage(String token) {
        return (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "picture.jpg", "image/jpeg", token)
            .expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
    }

    private static Map<String, Object> asset(String kind, String name, Object... more) {
        Map<String, Object> asset = body("kind", kind, "name", name, "language", "en");
        asset.putAll(body(more));
        return asset;
    }

    private String save(String token, Map<String, Object> asset) {
        return (String) run(AssetProcesses.SAVE, token, asset).get("assetId");
    }

    /** The use of a place, if any. */
    private static Map<String, Object> use(String targetKind, Object targetId) {
        List<Map<String, Object>> rows = query("SELECT * FROM qb_asset_use WHERE target_kind = ? AND target_id = ?",
            targetKind, String.valueOf(targetId));
        assertThat(rows).as("one use per place").hasSizeLessThanOrEqualTo(1);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static long uses(String sponsorId) {
        return (Long) query("SELECT count(*) AS n FROM qb_asset_use WHERE sponsor_id = ?", sponsorId).getFirst()
            .get("n");
    }

    private void allowPrizes(String sponsor) {
        run(com.jabiz.quizbuks.publishing.RewardMethodProcesses.SET, TestTokens.bearer(tokens, "fc1b-methods",
            QbPermissions.ADMIN_REWARD_METHODS), body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "F-C1b"));
    }

    /** A sponsor's library: an image (changed once: revision 2), a quiz introduction, an article, a prize. */
    private record Library(String picture, String image, String intro, String article, String redeem, String prize) {}

    private Library library(String token) {
        String picture = uploadImage(token);
        String image = save(token, asset("IMAGE", "Shop photo", "image", picture));
        Map<String, Object> renamed = asset("IMAGE", "Shop photo, spring", "image", picture, "baseRevision", 1);
        renamed.put("assetId", image);
        run(AssetProcesses.SAVE, token, renamed);
        return new Library(picture, image,
            save(token, asset("TEXT", "Intro", "usage", AssetRules.QUIZ_INTRO, "body", "Learn to brew.")),
            save(token, asset("ARTICLE", "Brewing", "body", "Grind, pour, wait.")),
            save(token, asset("TEXT", "At the counter", "usage", AssetRules.REDEEM_DESCRIPTION,
                "body", "A coffee at our counter")),
            save(token, asset("PRIZE", "Coffee", "prizeMethod", RewardMethod.OFFLINE_REDEEM,
                "prizeDescription", "A coffee at our counter")));
    }

    @Test
    void eachSaveRecordsTheAssetsItsPlacesWereCopiedFrom() {
        String ann = newSponsor(unique("s-fc1b-ann"));
        String token = sponsorToken(ann);
        Library lib = library(token);

        // A new quiz: its key is the place's.
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Coffee basics", "intro", "Learn to brew.",
            "cover", lib.picture(), "coverAssetId", lib.image(), "introAssetId", lib.intro())).get("quizId");
        assertThat(use(QbAssets.QUIZ_COVER, quizId)).containsEntry("asset_id", lib.image())
            .containsEntry("sponsor_id", ann).containsEntry("asset_revision", BigDecimal.valueOf(2));
        assertThat(use(QbAssets.QUIZ_COVER, quizId).get("used_at")).isNotNull();
        assertThat(use(QbAssets.QUIZ_INTRO, quizId)).containsEntry("asset_id", lib.intro())
            .containsEntry("asset_revision", BigDecimal.ONE);

        // A material: an article from an article, an image group from an image.
        String article = String.valueOf(run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE",
            "title", "Brewing", "body", "Grind, pour, wait.", "assetId", lib.article())).get("itemId"));
        assertThat(use(QbAssets.MATERIAL, article)).containsEntry("asset_id", lib.article());
        String pictures = String.valueOf(run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "IMAGES",
            "title", "Our shop", "images", List.of(body("image", lib.picture())), "assetId", lib.image()))
            .get("itemId"));
        assertThat(use(QbAssets.MATERIAL, pictures)).containsEntry("asset_id", lib.image());

        // A question and its new option: the keys the save made.
        Map<String, Object> question = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Which?",
            "image", lib.picture(), "imageAssetId", lib.image(), "options", List.of(
                body("image", lib.picture(), "imageAssetId", lib.image(), "correct", true), body("text", "none"))));
        String questionId = String.valueOf(question.get("itemId"));
        List<?> options = (List<?>) question.get("partIds");
        assertThat(use(QbAssets.QUESTION_IMAGE, questionId)).containsEntry("asset_id", lib.image());
        assertThat(use(QbAssets.OPTION_IMAGE, options.get(0))).containsEntry("asset_id", lib.image());
        assertThat(use(QbAssets.OPTION_IMAGE, options.get(1))).isNull();

        // A draft publication: its cover, its description and its prize.
        allowPrizes(ann);
        Map<String, Object> draft = complete(version(token, "Coffee quiz"), "Coffee week",
            clock.instant().plusSeconds(86_400), clock.instant().plusSeconds(8 * 86_400));
        draft.put("cover", lib.picture());
        draft.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        draft.put("redeemDescription", "A coffee at our counter");
        draft.put("assets", body("coverAssetId", lib.image(), "redeemAssetId", lib.redeem(),
            "prizeAssetId", lib.prize()));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, draft).get("publicationId");
        assertThat(use(QbAssets.PUBLICATION_COVER, publicationId)).containsEntry("asset_id", lib.image())
            .containsEntry("asset_revision", BigDecimal.valueOf(2));
        assertThat(use(QbAssets.PUBLICATION_REDEEM, publicationId)).containsEntry("asset_id", lib.redeem());
        assertThat(use(QbAssets.PUBLICATION_PRIZE, publicationId)).containsEntry("asset_id", lib.prize());
        assertThat(use(QbAssets.PUBLICATION_DIGITAL, publicationId)).isNull();
        assertThat(uses(ann)).isEqualTo(9);

        // The sponsor's own list: each place with its asset and where it is.
        List<Map<String, Object>> listed = items(USES, token, Map.of("limit", 100));
        assertThat(listed).hasSize(9).allSatisfy(row -> assertThat(row).containsEntry("stale", false));
        Map<String, Map<String, Object>> byPlace = listed.stream().collect(Collectors.toMap(
            row -> row.get("targetKind") + "/" + row.get("targetId"), row -> row));
        assertThat(byPlace.get(QbAssets.QUIZ_COVER + "/" + quizId)).containsEntry("quizId", quizId)
            .containsEntry("targetName", "Coffee basics").containsEntry("assetName", "Shop photo, spring")
            .containsEntry("assetKind", "IMAGE").containsEntry("assetRevision", 2).containsEntry("currentRevision", 2);
        assertThat(byPlace.get(QbAssets.MATERIAL + "/" + article)).containsEntry("quizId", quizId)
            .containsEntry("targetName", "Brewing");
        assertThat(byPlace.get(QbAssets.OPTION_IMAGE + "/" + options.get(0))).containsEntry("quizId", quizId)
            .containsEntry("targetName", "Coffee basics");
        assertThat(byPlace.get(QbAssets.PUBLICATION_PRIZE + "/" + publicationId))
            .containsEntry("publicationId", publicationId).containsEntry("targetName", "Coffee week");
        // Another sponsor sees none of them.
        assertThat(items(USES, sponsorToken(newSponsor(unique("s-fc1b-other"))), Map.of("limit", 100))).isEmpty();
    }

    @Test
    void onlyTheSponsorsOwnAssetsOfASuitableKindArePicked() {
        String ann = newSponsor(unique("s-fc1b-own"));
        String bob = newSponsor(unique("s-fc1b-bob"));
        String token = sponsorToken(ann);
        Library lib = library(token);
        String bobs = save(sponsorToken(bob), asset("LINK", "Bob's", "url", "https://bob.example"));
        String quizId = newQuiz(token, "Mine");
        Object revision = latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst()
            .get("revision");

        // Another's asset is not found, as one that does not exist; nothing is written.
        refused("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Mine", "introAssetId", bobs), 404);
        refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "Bob",
            "url", "https://bob.example", "assetId", bobs), 404);
        refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "Nobody",
            "url", "https://bob.example", "assetId", UUID.randomUUID().toString()), 404);
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("revision", revision).containsEntry("material_count", BigDecimal.ZERO);

        // A kind that does not suit the place: every one at once, by its field.
        assertThat(violations(refused("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Mine",
            "cover", lib.picture(), "intro", "Learn to brew.", "coverAssetId", lib.intro(),
            "introAssetId", lib.image()), 422))).extracting(v -> v.get("ruleCode") + " " + v.get("field"))
            .containsExactlyInAnyOrder(AssetCodes.TARGET_KIND + " coverAssetId",
                AssetCodes.TARGET_KIND + " introAssetId");
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "LINK", "title", "Photo",
            "url", "https://shop.example", "assetId", lib.image()), 422))).containsExactly(AssetCodes.TARGET_KIND);
        // A text of another use is another kind of text.
        assertThat(codes(refused("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE", "title",
            "Intro", "body", "Learn to brew.", "assetId", lib.intro()), 422))).containsExactly(AssetCodes.TARGET_KIND);
        assertThat(violations(refused("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Which?",
            "options", List.of(body("text", "a", "correct", true), body("text", "b", "imageAssetId", lib.article()))),
            422))).singleElement().satisfies(v -> assertThat(v).containsEntry("ruleCode", AssetCodes.TARGET_KIND)
                .containsEntry("field", "options[1].imageAssetId"));
        allowPrizes(ann);
        Map<String, Object> draft = complete(version(token, "Prize quiz"), "Prize week",
            clock.instant().plusSeconds(86_400), clock.instant().plusSeconds(8 * 86_400));
        draft.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        draft.put("redeemDescription", "A coffee at our counter");
        draft.put("assets", body("redeemAssetId", lib.prize(), "prizeAssetId", lib.redeem()));
        assertThat(violations(refused("QB_PUBLICATION_SAVE", token, draft, 422)))
            .extracting(v -> v.get("ruleCode") + " " + v.get("field"))
            .containsExactlyInAnyOrder(AssetCodes.TARGET_KIND + " assets.redeemAssetId",
                AssetCodes.TARGET_KIND + " assets.prizeAssetId");
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("revision", revision);
        assertThat(uses(ann)).isZero();
    }

    @Test
    void pickingAgainReplacesTheRecordAndPickingNothingKeepsIt() {
        String ann = newSponsor(unique("s-fc1b-again"));
        String token = sponsorToken(ann);
        Library lib = library(token);
        String second = save(token, asset("IMAGE", "Another photo", "image", uploadImage(token)));
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Again", "cover", lib.picture(),
            "coverAssetId", lib.image())).get("quizId");
        String first = String.valueOf(use(QbAssets.QUIZ_COVER, quizId).get("use_id"));

        String file = String.valueOf(query("SELECT image FROM qb_asset WHERE asset_id = ?", second).getFirst()
            .get("image"));
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Again", "cover", file, "coverAssetId", second));
        assertThat(use(QbAssets.QUIZ_COVER, quizId)).containsEntry("asset_id", second)
            .containsEntry("asset_revision", BigDecimal.ONE).doesNotContainEntry("use_id", first);
        // A save without an asset leaves the record as it is.
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Again, renamed", "cover", file));
        assertThat(use(QbAssets.QUIZ_COVER, quizId)).containsEntry("asset_id", second);
        // Picking the same again after the asset changed records its new revision.
        Map<String, Object> change = asset("IMAGE", "Another photo, cropped", "image", file);
        change.put("assetId", second);
        run(AssetProcesses.SAVE, token, change);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "Again, renamed", "cover", file,
            "coverAssetId", second));
        assertThat(use(QbAssets.QUIZ_COVER, quizId)).containsEntry("asset_id", second)
            .containsEntry("asset_revision", BigDecimal.valueOf(2));
        assertThat(uses(ann)).isOne();
    }

    @Test
    void savedFromTheLibraryTheContentIsWhatTypingItGives() {
        String ann = newSponsor(unique("s-fc1b-same"));
        String token = sponsorToken(ann);
        Library lib = library(token);
        String picked = (String) run("QB_QUIZ_SAVE", token, body("title", "Same", "intro", "Learn to brew.",
            "cover", lib.picture(), "coverAssetId", lib.image(), "introAssetId", lib.intro())).get("quizId");
        String typed = (String) run("QB_QUIZ_SAVE", token, body("title", "Same", "intro", "Learn to brew.",
            "cover", lib.picture())).get("quizId");
        Map<String, Object> pickedMaterial = run("QB_MATERIAL_SAVE", token, body("quizId", picked, "kind", "ARTICLE",
            "title", "Brewing", "body", "Grind, pour, wait.", "assetId", lib.article()));
        Map<String, Object> typedMaterial = run("QB_MATERIAL_SAVE", token, body("quizId", typed, "kind", "ARTICLE",
            "title", "Brewing", "body", "Grind, pour, wait."));
        assertThat(pickedMaterial.get("revision")).isEqualTo(typedMaterial.get("revision"));

        List<String> quizColumns = List.of("owner_id", "title", "intro", "cover", "time_limit_sec", "status",
            "removed", "revision", "changed_since_version", "question_count", "material_count");
        assertThat(columns(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + picked + "'").getFirst(),
            quizColumns)).isEqualTo(columns(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + typed + "'")
            .getFirst(), quizColumns));
        List<String> materialColumns = List.of("owner_id", "seq", "kind", "title", "description", "body", "url",
            "pdf", "audio");
        assertThat(columns(latest("qb_material_version", "material_id", "quiz_id = '" + picked + "'").getFirst(),
            materialColumns)).isEqualTo(columns(latest("qb_material_version", "material_id", "quiz_id = '" + typed
            + "'").getFirst(), materialColumns));
        // Only the records differ.
        assertThat(query("SELECT target_id FROM qb_asset_use WHERE sponsor_id = ?", ann))
            .extracting(row -> row.get("target_id")).containsOnly(picked, String.valueOf(pickedMaterial.get("itemId")));
    }

    private static Map<String, Object> columns(Map<String, Object> row, List<String> names) {
        Map<String, Object> picked = new LinkedHashMap<>();
        names.forEach(name -> picked.put(name, row.get(name)));
        return picked;
    }

    @Test
    void aChangedAssetShowsAsOlderContentAndASubmittedPublicationStaysAsItIs() {
        String ann = newSponsor(unique("s-fc1b-stale"));
        String token = sponsorToken(ann);
        Library lib = library(token);
        allowPrizes(ann);
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("redeemDescription", "A coffee at our counter");
        overrides.put("assets", body("redeemAssetId", lib.redeem()));
        String publicationId = livePublication(ann, "Coffee week", overrides);
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Kept", "cover", lib.picture(),
            "coverAssetId", lib.image())).get("quizId");

        Map<String, Object> change = asset("TEXT", "At the counter", "usage", AssetRules.REDEEM_DESCRIPTION,
            "body", "Two coffees at our counter", "baseRevision", 1);
        change.put("assetId", lib.redeem());
        assertThat(run(AssetProcesses.SAVE, token, change)).containsEntry("revision", 2);

        // The publication keeps what was copied; its place shows the older content, the quiz's does not.
        assertThat(row(publicationId)).containsEntry("redeem_description", "A coffee at our counter");
        Map<String, Map<String, Object>> byKind = items(USES, token, Map.of("limit", 100)).stream()
            .collect(Collectors.toMap(row -> String.valueOf(row.get("targetKind")), row -> row));
        assertThat(byKind.get(QbAssets.PUBLICATION_REDEEM)).containsEntry("stale", true)
            .containsEntry("assetRevision", 1).containsEntry("currentRevision", 2)
            .containsEntry("publicationId", publicationId).containsEntry("targetName", "Coffee week");
        assertThat(byKind.get(QbAssets.QUIZ_COVER)).containsEntry("stale", false).containsEntry("quizId", quizId);
        // A submitted publication is saved no more: its record stays as it was.
        assertThat(use(QbAssets.PUBLICATION_REDEEM, publicationId)).containsEntry("asset_revision", BigDecimal.ONE);
    }

    @Test
    void aPlaceDeletedTakesItsRecordWithIt() {
        String ann = newSponsor(unique("s-fc1b-gone"));
        String token = sponsorToken(ann);
        Library lib = library(token);
        String quizId = (String) run("QB_QUIZ_SAVE", token, body("title", "Gone", "intro", "Learn to brew.",
            "cover", lib.picture(), "coverAssetId", lib.image(), "introAssetId", lib.intro())).get("quizId");

        // A material.
        String material = String.valueOf(run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "ARTICLE",
            "title", "Brewing", "body", "Grind, pour, wait.", "assetId", lib.article())).get("itemId"));
        run("QB_MATERIAL_DELETE", token, body("quizId", quizId, "materialId", material));
        assertThat(use(QbAssets.MATERIAL, material)).isNull();

        // An option taken out of its question, then the question.
        Map<String, Object> question = run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Which?",
            "image", lib.picture(), "imageAssetId", lib.image(), "options", List.of(
                body("image", lib.picture(), "imageAssetId", lib.image(), "correct", true),
                body("image", lib.picture(), "imageAssetId", lib.image()))));
        String questionId = String.valueOf(question.get("itemId"));
        List<?> options = (List<?>) question.get("partIds");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "questionId", questionId, "stem", "Which?",
            "image", lib.picture(), "options", List.of(body("optionId", options.get(0), "image", lib.picture(),
                "correct", true))));
        assertThat(use(QbAssets.OPTION_IMAGE, options.get(1))).isNull();
        assertThat(use(QbAssets.OPTION_IMAGE, options.get(0))).isNotNull();
        assertThat(use(QbAssets.QUESTION_IMAGE, questionId)).isNotNull();
        run("QB_QUESTION_DELETE", token, body("quizId", quizId, "questionId", questionId));
        assertThat(use(QbAssets.OPTION_IMAGE, options.get(0))).isNull();
        assertThat(use(QbAssets.QUESTION_IMAGE, questionId)).isNull();

        // A draft publication.
        allowPrizes(ann);
        Map<String, Object> draft = complete(version(token, "Draft quiz"), "Draft week",
            clock.instant().plusSeconds(86_400), clock.instant().plusSeconds(8 * 86_400));
        draft.put("cover", lib.picture());
        draft.put("assets", body("coverAssetId", lib.image()));
        String publicationId = (String) run("QB_PUBLICATION_SAVE", token, draft).get("publicationId");
        assertThat(use(QbAssets.PUBLICATION_COVER, publicationId)).isNotNull();
        run("QB_PUBLICATION_DELETE", token, body("publicationId", publicationId));
        assertThat(use(QbAssets.PUBLICATION_COVER, publicationId)).isNull();

        // A quiz removed: its own places and its parts'.
        String kept = String.valueOf(run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "IMAGES",
            "title", "Shop", "images", List.of(body("image", lib.picture())), "assetId", lib.image())).get("itemId"));
        assertThat(use(QbAssets.MATERIAL, kept)).isNotNull();
        run("QB_QUIZ_DELETE", token, body("quizId", quizId));
        assertThat(use(QbAssets.QUIZ_COVER, quizId)).isNull();
        assertThat(use(QbAssets.QUIZ_INTRO, quizId)).isNull();
        assertThat(use(QbAssets.MATERIAL, kept)).isNull();
        assertThat(uses(ann)).isZero();

        // An asset deleted takes its records with it; what was copied stays.
        String other = (String) run("QB_QUIZ_SAVE", token, body("title", "Other", "intro", "Learn to brew.",
            "introAssetId", lib.intro())).get("quizId");
        assertThat(run(AssetProcesses.DELETE, token, body("assetId", lib.intro()))).containsEntry("uses", 1);
        assertThat(use(QbAssets.QUIZ_INTRO, other)).isNull();
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + other + "'").getFirst())
            .containsEntry("intro", "Learn to brew.");
    }

    @Test
    void thePlatformsSweepKeepsAFileOnlyAnAssetRefersTo() {
        String ann = newSponsor(unique("s-fc1b-sweep"));
        String token = sponsorToken(ann);
        String kept = uploadImage(token);
        save(token, asset("IMAGE", "Only in the library", "image", kept));
        String unused = uploadImage(token);

        clock.advance(Duration.ofDays(2));
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        assertThat(jobRunner.run(sweep, clock.instant())).isEqualTo(JobRunner.Outcome.SUCCEEDED);
        // The storage half runs after the commit.
        await().atMost(Duration.ofSeconds(30)).until(() -> query("SELECT 1 FROM op_process p "
            + "WHERE p.process_name = 'FILE_PURGE_ORPHANS' AND NOT EXISTS (SELECT 1 FROM op_process_after_commit a "
            + "WHERE a.process_seq_id = p.process_seq_id AND a.succeeded)").isEmpty());

        assertThat(query("SELECT 1 FROM sys_file WHERE file_id = ?", UUID.fromString(kept))).hasSize(1);
        assertThat(query("SELECT 1 FROM sys_file WHERE file_id = ?", UUID.fromString(unused))).isEmpty();
    }
}
