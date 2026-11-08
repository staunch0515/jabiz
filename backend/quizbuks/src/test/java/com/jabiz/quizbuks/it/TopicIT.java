package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.QuizTopics;
import com.jabiz.quizbuks.publishing.PublicationTopics;
import com.jabiz.quizbuks.publishing.PublicationVersions;
import com.jabiz.quizbuks.topic.TopicCheck;
import com.jabiz.quizbuks.topic.TopicCodes;
import com.jabiz.quizbuks.topic.TopicProcesses;
import com.jabiz.quizbuks.topic.TopicRules;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S9 (docs/quizbuks/plans/impl/F-S9.md): headquarters keeps the word list (add, rename, deactivate, merge; audited);
 * a quiz's topics go into its versions, a publication inherits its version's and adds or removes some, frozen on its
 * card at go-live and at a switch of version; strict mode leaves the home and its shelf of shorts only the
 * publications sharing a word of the list with the taker's liked topics (the template compared with
 * {@link TopicRules#strictAllows}); the figures by topic give users only from the minimum group up.
 */
class TopicIT extends PlayItSupport {

    @Autowired
    Environment environment;

    @Autowired
    EntityDefinitionRegistry entities;

    @AfterEach
    void onlyInserted() {
        assertNothingUpdated();
    }

    String topicAdmin() {
        return TestTokens.bearer(tokens, "topic-admin", QbPermissions.ADMIN_TOPIC_READ,
            QbPermissions.ADMIN_TOPIC_WRITE);
    }

    /** A word of the list, added the first time. */
    String word(String code) {
        if (query("SELECT 1 FROM qb_topic WHERE topic_id = ?", code).isEmpty()) {
            run(TopicProcesses.ADD, topicAdmin(), body("topicId", code, "nameZh", code + " 中", "nameJa", code + " 日",
                "nameEn", code + " en"));
        }
        return code;
    }

    /** A complete quiz with these topics, saved as a version; the version's key. */
    String topicVersion(String token, String title, List<String> topics, List<String> custom) {
        String quizId = newQuiz(token, title);
        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", title, "timeLimitSec", 300));
        run(QuizTopics.SET, token, body("quizId", quizId, "topics", topics, "customTopics", custom));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        return (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
    }

    /** A draft of the version, aimed at everybody, live for a week once submitted. */
    String topicDraft(String token, String versionId, String name) {
        Instant now = clock.instant();
        Map<String, Object> save = complete(versionId, name, now, now.plus(Duration.ofDays(7)));
        save.put("maxWinners", 1000);
        save.put("regions", List.of("GLOBAL"));
        return (String) run("QB_PUBLICATION_SAVE", token, save).get("publicationId");
    }

    String goLive(String token, String publicationId) {
        assertThat(run("QB_PUBLICATION_SUBMIT", token, body("publicationId", publicationId)).get("status"))
            .isEqualTo("LIVE");
        return publicationId;
    }

    /** A live publication of a version with these topics (and no changes of its own). */
    String topicPublication(String sponsor, String name, List<String> topics, List<String> custom) {
        String token = sponsorToken(sponsor);
        return goLive(token, topicDraft(token, topicVersion(token, name + " quiz", topics, custom), name));
    }

    Map<String, Object> settings(String token, Map<String, Object> input) {
        return run("QB_SETTINGS_SAVE", token, input);
    }

    @Test
    void headquartersKeepsTheWordListWithAnAudit() {
        String admin = topicAdmin();
        Map<String, Object> added = run(TopicProcesses.ADD, admin, body("topicId", "fs9-cooking", "nameZh", "烹饪",
            "nameJa", "料理", "nameEn", "Cooking"));
        assertThat(added).containsEntry("topicId", "fs9-cooking").containsEntry("active", true);
        assertThat(codes(refused(TopicProcesses.ADD, admin, body("topicId", "Fs9 Cooking", "nameZh", "a", "nameJa", "a",
            "nameEn", "a"), 422))).containsExactly(TopicCodes.CODE_FORMAT);
        assertThat(codes(refused(TopicProcesses.ADD, admin, body("topicId", "fs9-cooking", "nameZh", "a", "nameJa", "a",
            "nameEn", "a"), 422))).containsExactly(TopicCodes.CODE_TAKEN);
        // Every language is required (the request is refused before it is run).
        refused(TopicProcesses.ADD, admin, body("topicId", "fs9-half", "nameZh", "半", "nameJa", "", "nameEn", "Half"),
            400);
        run(TopicProcesses.RENAME, admin, body("topicId", "fs9-cooking", "nameZh", "烹调", "nameJa", "料理",
            "nameEn", "Cooking"));
        assertThat(String.valueOf(query("SELECT * FROM qb_topic WHERE topic_id = 'fs9-cooking'").getFirst().get("name")))
            .contains("烹调");
        run(TopicProcesses.DEACTIVATE, admin, body("topicId", "fs9-cooking"));
        assertThat(query("SELECT * FROM qb_topic WHERE topic_id = 'fs9-cooking'").getFirst()).containsEntry("active",
            false);
        run(TopicProcesses.REACTIVATE, admin, body("topicId", "fs9-cooking"));

        // A merge: the word counts as the other from now on; those merged into it move along (one step always).
        word("fs9-food");
        word("fs9-recipes");
        word("fs9-cuisine");
        run(TopicProcesses.MERGE, admin, body("topicId", "fs9-recipes", "into", "fs9-cooking"));
        run(TopicProcesses.MERGE, admin, body("topicId", "fs9-cooking", "into", "fs9-food"));
        assertThat(query("SELECT * FROM qb_topic WHERE topic_id IN ('fs9-recipes', 'fs9-cooking')"))
            .allSatisfy(row -> assertThat(row).containsEntry("merged_into", "fs9-food").containsEntry("active", false));
        assertThat(codes(refused(TopicProcesses.RENAME, admin, body("topicId", "fs9-cooking", "nameZh", "x", "nameJa",
            "x", "nameEn", "x"), 422))).containsExactly(TopicCodes.MERGED);
        assertThat(codes(refused(TopicProcesses.REACTIVATE, admin, body("topicId", "fs9-cooking"), 422)))
            .containsExactly(TopicCodes.MERGED);
        assertThat(codes(refused(TopicProcesses.MERGE, admin, body("topicId", "fs9-cuisine", "into", "fs9-recipes"),
            422))).containsExactly(TopicCodes.MERGE_TARGET);
        assertThat(codes(refused(TopicProcesses.MERGE, admin, body("topicId", "fs9-cuisine", "into", "fs9-cuisine"),
            422))).containsExactly(TopicCodes.MERGE_TARGET);
        refused(TopicProcesses.RENAME, admin, body("topicId", "fs9-none", "nameZh", "x", "nameJa", "x", "nameEn", "x"),
            404);
        // Only headquarters: a sponsor or a taker may not.
        refused(TopicProcesses.ADD, sponsorToken(newSponsor("s-fs9-words")), body("topicId", "fs9-x", "nameZh", "x",
            "nameJa", "x", "nameEn", "x"), 403);
        // The platform's write path audited every change.
        assertThat(query("SELECT action FROM sys_audit_record WHERE entity_type = 'QbTopic' AND entity_id = ?",
            "fs9-cooking")).extracting(row -> row.get("action")).contains("INSERT", "UPDATE");
    }

    @Test
    void aQuizsTopicsGoIntoItsVersionsAndAPublicationAddsOrRemovesSome() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs9-inherit");
        credit(sponsor, 10_000);
        String token = sponsorToken(sponsor);
        word("fs9-history");
        word("fs9-art");
        word("fs9-music");
        word("fs9-old");
        // A word chosen anew must be offered; one the quiz already has stays though deactivated since.
        String quizId = newQuiz(token, "FS9 inherit");
        assertThat(codes(refused(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of("fs9-nowhere")), 422)))
            .containsExactly(TopicCodes.NOT_SELECTABLE);
        run(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of("fs9-history", "fs9-old"),
            "customTopics", List.of("  Edo   period ", "edo period", "Ukiyo-e")));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("topics", "fs9-history,fs9-old")
            .containsEntry("custom_topics", "[\"Edo period\",\"Ukiyo-e\"]");
        run(TopicProcesses.DEACTIVATE, topicAdmin(), body("topicId", "fs9-old"));
        run(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of("fs9-old", "fs9-history", "fs9-art"),
            "customTopics", List.of("Edo period", "Ukiyo-e")));
        // The limits: the parameters (not controlled).
        run("PARAM_SET", admin(), body("key", "qb.topic.custom-max-length", "value", "5"));
        assertThat(codes(refused(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of(), "customTopics",
            List.of("Too long")), 422))).containsExactly(TopicCodes.CUSTOM_LENGTH);
        run("PARAM_SET", admin(), body("key", "qb.topic.custom-max-length", "value", "30"));
        run("PARAM_SET", admin(), body("key", "qb.topic.max-per-quiz", "value", "4"));
        assertThat(codes(refused(QuizTopics.SET, token, body("quizId", quizId, "topics",
            List.of("fs9-old", "fs9-history", "fs9-art"), "customTopics", List.of("a", "b")), 422)))
            .containsExactly(TopicCodes.TOO_MANY);
        run("PARAM_SET", admin(), body("key", "qb.topic.max-per-quiz", "value", "10"));

        run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "FS9 inherit", "timeLimitSec", 300));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
            "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
            "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
        String first = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        Map<String, Object> version = latest("qb_quiz_version_version", "version_id", "version_id = '" + first + "'")
            .getFirst();
        assertThat(version).containsEntry("topics", "fs9-old,fs9-history,fs9-art")
            .containsEntry("custom_topics", "[\"Edo period\",\"Ukiyo-e\"]");
        // A change of the topics alone is a new version (the content's hash is the same).
        run(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of("fs9-history", "fs9-art"),
            "customTopics", List.of("Edo period", "Ukiyo-e")));
        String second = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
        assertThat(latest("qb_quiz_version_version", "version_id", "version_id = '" + second + "'").getFirst())
            .containsEntry("content_hash", version.get("content_hash")).containsEntry("topics", "fs9-history,fs9-art");

        // The publication inherits the first version's, leaves out one word and one custom word, adds its own.
        String publicationId = topicDraft(token, first, "FS9 inherit pub");
        // A deactivated word the version has is inherited; one it lacks is not added anew.
        run(TopicProcesses.DEACTIVATE, topicAdmin(), body("topicId", word("fs9-retired")));
        assertThat(codes(refused(PublicationTopics.SET, token, body("publicationId", publicationId, "add",
            List.of("fs9-retired")), 422))).containsExactly(TopicCodes.NOT_SELECTABLE);

        run(PublicationTopics.SET, token, body("publicationId", publicationId, "add",
            List.of("fs9-music", "fs9-history"), "remove", List.of("fs9-old", "fs9-not-there"),
            "customAdd", List.of("Hokusai"), "customRemove", List.of("ukiyo-E")));
        // Kept against the version: only additions it lacks and removals it has.
        assertThat(latest("qb_publication_version", "publication_id", "publication_id = '" + publicationId + "'")
            .getFirst().get("topic_changes")).isEqualTo("{\"add\":[\"fs9-music\"],\"remove\":[\"fs9-old\"],"
            + "\"customAdd\":[\"Hokusai\"],\"customRemove\":[\"ukiyo-E\"]}");
        // Another sponsor's draft is not found.
        refused(PublicationTopics.SET, sponsorToken(newSponsor("s-fs9-other")), body("publicationId", publicationId,
            "add", List.of("fs9-art")), 404);
        goLive(token, publicationId);
        assertThat(card(publicationId)).containsEntry("topics", "fs9-history,fs9-art,fs9-music")
            .containsEntry("custom_topics", "[\"Edo period\",\"Hokusai\"]");
        // Frozen: submitted, the changes no longer change.
        assertThat(codes(refused(PublicationTopics.SET, token, body("publicationId", publicationId, "add",
            List.of()), 422))).containsExactly("QB_PUBLICATION_NOT_DRAFT");
        // A copy carries the changes.
        String copy = (String) run("QB_PUBLICATION_COPY", token, body("publicationId", publicationId))
            .get("publicationId");
        assertThat(latest("qb_publication_version", "publication_id", "publication_id = '" + copy + "'").getFirst()
            .get("topic_changes")).isEqualTo(latest("qb_publication_version", "publication_id",
            "publication_id = '" + publicationId + "'").getFirst().get("topic_changes"));
        // A switch of version applies the same changes to the new version's topics.
        run(PublicationVersions.SWITCH, token, body("publicationId", publicationId, "versionId", second));
        assertThat(card(publicationId)).containsEntry("version_id", UUID.fromString(second))
            .containsEntry("topics", "fs9-history,fs9-art,fs9-music");
        // The sponsor's version list gives the version's topics, for the wizard.
        assertThat(items("qb.sponsor.quiz-versions", token, Map.of("params", Map.of("quizId", quizId))))
            .filteredOn(row -> first.equals(row.get("versionId"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("topics", "fs9-old,fs9-history,fs9-art"));
    }

    @Test
    void aCopyOfAQuizTakesItsTopics() {
        String token = sponsorToken(newSponsor("s-fs9-clone"));
        word("fs9-science");
        String versionId = topicVersion(token, "FS9 clone", List.of("fs9-science"), List.of("Atoms"));
        String quizId = String.valueOf(latest("qb_quiz_version_version", "version_id", "version_id = '" + versionId
            + "'").getFirst().get("quiz_id"));
        String copy = String.valueOf(run("QB_QUIZ_CLONE", token, body("quizId", quizId, "versionNo", 1))
            .get("quizId"));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + copy + "'").getFirst())
            .containsEntry("topics", "fs9-science").containsEntry("custom_topics", "[\"Atoms\"]");
    }

    /**
     * The comparison test of strict mode: for takers with and without strict mode and various liked topics, the home
     * (and its shelf of shorts, the same template) lists exactly the publications {@link TopicRules#strictAllows}
     * allows; a publication with custom words only, or no words, never shows in strict mode; a word merged counts as
     * the one it was merged into.
     */
    @Test
    void strictModeShowsOnlyPublicationsSharingALikedWord() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs9-strict");
        credit(sponsor, 100_000);
        word("fs9-sport");
        word("fs9-travel");
        word("fs9-games");
        word("fs9-hiking");
        Map<String, List<String>> topicsOf = new LinkedHashMap<>();
        Map<String, String> publications = new LinkedHashMap<>();
        for (List<String> topics : List.of(List.of("fs9-sport"), List.of("fs9-travel"),
            List.of("fs9-sport", "fs9-travel"), List.of("fs9-hiking"), List.<String>of())) {
            String name = "FS9 strict " + String.join("+", topics);
            String id = topicPublication(sponsor, name, topics, List.of());
            publications.put(name, id);
            topicsOf.put(id, topics);
        }
        // Custom words only: for search, never for strict mode, however they are written.
        String customOnly = topicPublication(sponsor, "FS9 strict custom", List.of(), List.of("fs9-sport", "Sport"));
        topicsOf.put(customOnly, List.of());
        // After the publications went on, hiking is merged into travel: who likes travel sees hiking's.
        run(TopicProcesses.MERGE, topicAdmin(), body("topicId", "fs9-hiking", "into", "fs9-travel"));
        Map<String, TopicRules.Word> words = new LinkedHashMap<>();
        for (Map<String, Object> row : query("SELECT * FROM qb_topic WHERE topic_id LIKE 'fs9-%'")) {
            words.put(String.valueOf(row.get("topic_id")), new TopicRules.Word(String.valueOf(row.get("topic_id")),
                Boolean.TRUE.equals(row.get("active")), (String) row.get("merged_into")));
        }

        int index = 0;
        for (List<String> liked : List.of(List.of("fs9-sport"), List.of("fs9-travel"), List.of("fs9-games"),
            List.of("fs9-sport", "fs9-games"))) {
            for (boolean strict : List.of(false, true)) {
                String taker = taker(newTaker("t-fs9-strict-" + index++, "JP"));
                settings(taker, body("likedTopics", liked, "strictTopics", strict));
                List<Object> listed = feed(taker, "JP", Map.of("q", "FS9 strict")).stream()
                    .map(row -> row.get("publicationId")).toList();
                List<Object> expected = new ArrayList<>();
                topicsOf.forEach((id, topics) -> {
                    if (TopicRules.strictAllows(strict, liked, topics, words)) {
                        expected.add(id);
                    }
                });
                assertThat(listed).as(liked + " strict " + strict).containsExactlyInAnyOrderElementsOf(expected);
                if (strict) {
                    assertThat(listed).doesNotContain(customOnly, publications.get("FS9 strict "));
                }
            }
        }
        // Concretely: travel with strict mode shows travel, sport+travel and hiking (merged into travel).
        String traveller = taker(newTaker("t-fs9-traveller", "JP"));
        settings(traveller, body("likedTopics", List.of("fs9-travel"), "strictTopics", true));
        assertThat(feed(traveller, "JP", Map.of("q", "FS9 strict"))).extracting(row -> row.get("publicationId"))
            .containsExactlyInAnyOrder(publications.get("FS9 strict fs9-travel"),
                publications.get("FS9 strict fs9-sport+fs9-travel"), publications.get("FS9 strict fs9-hiking"));
        // Turned off, the home is as before.
        settings(traveller, body("strictTopics", false));
        assertThat(feed(traveller, "JP", Map.of("q", "FS9 strict"))).hasSize(topicsOf.size());
    }

    @Test
    void theShelfOfShortsFollowsStrictMode() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs9-shorts");
        credit(sponsor, 10_000);
        word("fs9-comedy");
        word("fs9-news");
        String token = sponsorToken(sponsor);
        List<String> shorts = new ArrayList<>();
        for (String topic : List.of("fs9-comedy", "fs9-news")) {
            String quizId = newQuiz(token, "FS9 short " + topic);
            run("QB_QUIZ_SAVE", token, body("quizId", quizId, "title", "FS9 short " + topic, "timeLimitSec", 300));
            run(QuizTopics.SET, token, body("quizId", quizId, "topics", List.of(topic)));
            run("QB_MATERIAL_SAVE", token, body("quizId", quizId, "kind", "VIDEO_LINK", "title", "Film", "url",
                "https://www.youtube.com/shorts/dQw4w9WgXcQ"));
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of France?", "points", 2,
                "options", List.of(body("text", "Paris", "correct", true), body("text", "Lyon"))));
            run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "Capital of Japan?", "points", 3,
                "options", List.of(body("text", "Tokyo", "correct", true), body("text", "Osaka"))));
            String versionId = (String) run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId)).get("versionId");
            shorts.add(goLive(token, topicDraft(token, versionId, "FS9 short " + topic)));
        }
        String taker = taker(newTaker("t-fs9-shorts", "JP"));
        settings(taker, body("likedTopics", List.of("fs9-comedy"), "strictTopics", true));
        assertThat(feed(taker, "JP", Map.of("shorts", true, "q", "FS9 short")))
            .extracting(row -> row.get("publicationId")).containsExactly(shorts.getFirst());
    }

    @Test
    void strictModeNeedsALikedTopicAndTurnsOffAnyTime() {
        word("fs9-pets");
        word("fs9-dropped");
        String token = taker(newTaker("t-fs9-settings", "JP"));
        assertThat(codes(refused("QB_SETTINGS_SAVE", token, body("strictTopics", true), 422)))
            .containsExactly(TopicCodes.STRICT_NEEDS_TOPICS);
        assertThat(codes(refused("QB_SETTINGS_SAVE", token, body("likedTopics", List.of("fs9-unknown")), 422)))
            .containsExactly(TopicCodes.NOT_SELECTABLE);
        Map<String, Object> saved = settings(token, body("likedTopics", List.of("fs9-pets", "fs9-dropped"),
            "strictTopics", true));
        assertThat(saved).containsEntry("likedTopics", List.of("fs9-pets", "fs9-dropped"))
            .containsEntry("strictTopics", true);
        // A word liked before stays liked though deactivated since; it cannot be liked anew by another.
        run(TopicProcesses.DEACTIVATE, topicAdmin(), body("topicId", "fs9-dropped"));
        settings(token, body("likedTopics", List.of("fs9-dropped", "fs9-pets")));
        assertThat(codes(refused("QB_SETTINGS_SAVE", taker(newTaker("t-fs9-settings-2", "JP")),
            body("likedTopics", List.of("fs9-dropped")), 422))).containsExactly(TopicCodes.NOT_SELECTABLE);
        // None left while on is refused; off works whatever is liked.
        assertThat(codes(refused("QB_SETTINGS_SAVE", token, body("likedTopics", List.of()), 422)))
            .containsExactly(TopicCodes.STRICT_NEEDS_TOPICS);
        assertThat(settings(token, body("strictTopics", false))).containsEntry("strictTopics", false);
        assertThat(settings(token, body("likedTopics", List.of()))).containsEntry("likedTopics", List.of());
        // The other settings are as they were.
        assertThat(settings(token, body("soundOn", false))).containsEntry("soundOn", false)
            .containsEntry("strictTopics", false);
    }

    @Test
    void theFiguresByTopicGiveUsersFromTheMinimumGroupUp() {
        autoApprove(true);
        String sponsor = newSponsor("s-fs9-figures");
        credit(sponsor, 10_000);
        word("fs9-popular");
        word("fs9-rare");
        word("fs9-alias");
        topicPublication(sponsor, "FS9 figures popular", List.of("fs9-popular"), List.of("fs9-rare"));
        topicPublication(sponsor, "FS9 figures alias", List.of("fs9-alias", "fs9-popular"), List.of());
        run(TopicProcesses.MERGE, topicAdmin(), body("topicId", "fs9-alias", "into", "fs9-popular"));
        for (int i = 0; i < 5; i++) {
            settings(taker(newTaker("t-fs9-fig-" + i, "JP")), body("likedTopics", List.of("fs9-popular"),
                "strictTopics", i == 0));
        }
        settings(taker(newTaker("t-fs9-fig-rare", "JP")), body("likedTopics", List.of("fs9-rare")));
        List<Map<String, Object>> rows = items("qb.admin.topics", topicAdmin(), Map.of("limit", 200));
        Map<String, Map<String, Object>> byCode = new LinkedHashMap<>();
        rows.forEach(row -> byCode.put(String.valueOf(row.get("topicId")), row));
        // Two cards count for popular (the alias's card counts once); the custom word never counts.
        assertThat(byCode.get("fs9-popular")).containsEntry("publications", 2).containsEntry("livePublications", 2)
            .containsEntry("likedBy", 5).containsEntry("strictBy", null).containsEntry("minGroup", 5);
        assertThat(byCode.get("fs9-rare")).containsEntry("publications", 0).containsEntry("likedBy", null);
        // A merged word's figures are on the word it counts as.
        assertThat(byCode.get("fs9-alias")).containsEntry("mergedInto", "fs9-popular")
            .containsEntry("publications", null);
        // Headquarters only.
        refusedQuery("qb.admin.topics", sponsorToken(sponsor));
    }

    void refusedQuery(String id, String token) {
        client.post().uri("/api/queries/" + id).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
            .header(org.springframework.http.HttpHeaders.AUTHORIZATION, token).bodyValue(Map.of()).exchange()
            .expectStatus().isForbidden();
    }

    @Test
    void takersAndSponsorsReadTheWordList() {
        word("fs9-read");
        assertThat(items("qb.app.topics", taker(newTaker("t-fs9-read", "JP")), Map.of("limit", 500)))
            .filteredOn(row -> "fs9-read".equals(row.get("topicId"))).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("name"))).contains("fs9-read en"));
        assertThat(items("qb.sponsor.topics", sponsorToken(newSponsor("s-fs9-read")), Map.of("limit", 500)))
            .extracting(row -> row.get("topicId")).contains("fs9-read");
    }

    /** The startup check: a word without its name in a language (written otherwise than by the processes) is an error. */
    @Test
    void aWordWithoutANameInSomeLanguageIsAStartupError() {
        TopicCheck check = new TopicCheck(environment, entities);
        assertThat(check.check()).noneMatch(problem -> problem.location().startsWith("topic"));
        execute("INSERT INTO qb_topic (topic_id, name, active, created_at, changed_at, version) VALUES "
            + "('fs9-broken', '{\"zh\": \"坏\", \"en\": \" \"}'::jsonb, true, now(), now(), 1)");
        try {
            assertThat(check.check()).filteredOn(problem -> problem.location().equals("topic:fs9-broken"))
                .singleElement().satisfies(problem -> {
                    assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.ERROR);
                    assertThat(problem.message()).contains("ja, en");
                });
        } finally {
            execute("DELETE FROM qb_topic WHERE topic_id = 'fs9-broken'");
        }
    }
}
