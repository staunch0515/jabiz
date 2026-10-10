package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * Editing quizzes (M-20 – M-24, M-27; docs/quizbuks/plans/Q3-content.md): drafts may be incomplete, versions may
 * not; limits, kinds, revisions, order and the two templates.
 */
class ContentIT extends ContentItSupport {

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    @Test
    void aDraftNeedsOnlyATitle() {
        Map<String, Object> saved = run("QB_QUIZ_SAVE", sponsorA(), body("title", "Only a title"));
        assertThat(saved).containsEntry("revision", 1);
        String quizId = (String) saved.get("quizId");
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'")).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("owner_id", "sponsor-a").containsEntry("status", "DRAFT")
                .containsEntry("removed", false).containsEntry("ai_generated", false));

        assertThat(codes(refused("QB_QUIZ_SAVE", sponsorA(), body("title", "  "), 400)))
            .containsExactly(ContentCodes.QUIZ_TITLE_BLANK);
        assertThat(codes(refused("QB_QUIZ_SAVE", sponsorA(), body("title", "T", "timeLimitSec", 10), 400)))
            .containsExactly(ContentCodes.QUIZ_TIME_LIMIT_RANGE);
        refused("QB_QUIZ_SAVE", sponsorA(), body("title", "x".repeat(101)), 400);

        // Saving the same heading again changes nothing.
        Map<String, Object> again = run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Only a title"));
        assertThat(again).containsEntry("revision", 1);
        Map<String, Object> changed = run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "New title",
            "intro", "Some *Markdown*", "timeLimitSec", 600, "baseRevision", 1));
        assertThat(changed).containsEntry("revision", 2);
    }

    @Test
    void materialsKeepToTheirKind() {
        String quizId = newQuiz(sponsorA(), "Materials");
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE", "title", "Article",
            "description", "What to read", "body", "# Heading\n\nText"));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK", "title", "Link",
            "url", "https://example.com/page?x=1"));
        String video = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "VIDEO_LINK",
            "title", "Video", "url", "http://video.example/v/1")).get("itemId");

        assertThat(violations(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK",
            "title", "Two things", "url", "https://example.com", "body", "text"), 422)))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode"))
            .containsExactly(tuple("body", ContentCodes.MATERIAL_FIELD_NOT_ALLOWED));
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE",
            "title", "Images in an article", "images", List.of(body("image", UUID.randomUUID()))), 422)))
            .containsExactly(ContentCodes.MATERIAL_FIELD_NOT_ALLOWED);
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK",
            "title", "Script", "url", "javascript:alert(1)"), 400))).containsExactly(ContentCodes.MATERIAL_URL_FORMAT);
        refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK", "url", "https://a b.example"),
            400);
        refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "VIDEO"), 400);
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "materialId", video,
            "kind", "LINK", "url", "https://example.com"), 422))).containsExactly(ContentCodes.MATERIAL_KIND_FIXED);

        List<Object> images = new ArrayList<>();
        for (int i = 0; i <= ContentLimits.MAX_IMAGES; i++) {
            images.add(body("image", UUID.randomUUID()));
        }
        refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "IMAGES", "images", images), 400);

        for (int i = 3; i < ContentLimits.MAX_MATERIALS; i++) {
            run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE", "title", "M" + i));
        }
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE"), 422)))
            .containsExactly(ContentCodes.TOO_MANY_MATERIALS);
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst()
            .get("material_count").toString()).isEqualTo("10");
    }

    @Test
    void questionsSaveTheirOptionsAndOnlyWhatChanged() {
        String quizId = newQuiz(sponsorA(), "Questions");
        Map<String, Object> first = run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "2 + 2?",
            "points", 2, "options", List.of(body("text", "3"), body("text", "4", "correct", true),
                body("text", "5"))));
        String questionId = (String) first.get("itemId");
        @SuppressWarnings("unchecked")
        List<String> options = (List<String>) first.get("partIds");
        assertThat(options).hasSize(3);
        long rowsBefore = count("qb_option_version");

        // The second option is kept and moves first, the first is dropped, the third changes, one is new.
        Map<String, Object> second = run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId",
            questionId, "stem", "2 + 2?", "points", 2, "options", List.of(
                body("optionId", options.get(1), "text", "4", "correct", true),
                body("optionId", options.get(2), "text", "five"), body("text", "22"))));
        @SuppressWarnings("unchecked")
        List<String> after = (List<String>) second.get("partIds");
        assertThat(after.subList(0, 2)).containsExactly(options.get(1), options.get(2));
        // A delete, two updates (the kept option moved, the third changed text and place) and an insert.
        assertThat(count("qb_option_version") - rowsBefore).isEqualTo(4);
        assertThat(latest("qb_option_version", "option_id", "question_id = '" + questionId + "' AND NOT is_deleted"))
            .extracting(row -> row.get("text")).containsExactlyInAnyOrder("4", "five", "22");

        // Unchanged: nothing written.
        long quizRows = count("qb_quiz_version");
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", questionId, "stem", "2 + 2?",
            "points", 2, "options", List.of(body("optionId", after.get(0), "text", "4", "correct", true),
                body("optionId", after.get(1), "text", "five"), body("optionId", after.get(2), "text", "22"))));
        assertThat(count("qb_quiz_version")).isEqualTo(quizRows);

        List<Object> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(body("text", "o" + i));
        }
        refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "options", seven), 400);
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "points", 0), 400)))
            .containsExactly(ContentCodes.QUESTION_POINTS_RANGE);
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId",
            UUID.randomUUID()), 422))).containsExactly(ContentCodes.CONTENT_NOT_IN_QUIZ);
        // An option of another question is not this question's.
        String other = question(sponsorA(), quizId, "Other");
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", other,
            "options", List.of(body("optionId", after.get(0), "text", "x"))), 422)))
            .containsExactly(ContentCodes.CONTENT_NOT_IN_QUIZ);

        run("QB_QUESTION_DELETE", sponsorA(), body("quizId", quizId, "questionId", questionId));
        assertThat(latest("qb_option_version", "option_id", "question_id = '" + questionId + "' AND NOT is_deleted"))
            .isEmpty();
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst()
            .get("question_count").toString()).isEqualTo("1");
    }

    @Test
    void withoutAListThePartsStayAndAnEmptyListRemovesThem() {
        String quizId = newQuiz(sponsorA(), "Parts");
        String questionId = question(sponsorA(), quizId, "Q");
        // No options given: the text changes, the two options stay.
        Map<String, Object> kept = run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId",
            questionId, "stem", "Q, reworded"));
        assertThat((List<?>) kept.get("partIds")).hasSize(2);
        assertThat(latest("qb_option_version", "option_id", "question_id = '" + questionId + "' AND NOT is_deleted"))
            .hasSize(2);
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", questionId, "stem", "Q",
            "options", List.of()));
        assertThat(latest("qb_option_version", "option_id", "question_id = '" + questionId + "' AND NOT is_deleted"))
            .isEmpty();

        // The same for the images of an image group (no files needed to show it: the list is never read).
        String material = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "IMAGES",
            "title", "Pictures")).get("itemId");
        Map<String, Object> renamed = run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "materialId",
            material, "kind", "IMAGES", "title", "Photos"));
        assertThat((List<?>) renamed.get("partIds")).isEmpty();
        assertThat(latest("qb_material_version", "material_id", "material_id = '" + material + "'").getFirst())
            .containsEntry("title", "Photos");
    }

    @Test
    void aQuizHasAtMostAHundredQuestions() {
        String quizId = newQuiz(sponsorA(), "Many");
        for (int i = 0; i < ContentLimits.MAX_QUESTIONS; i++) {
            run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Q" + i));
        }
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "one more"), 422)))
            .containsExactly(ContentCodes.TOO_MANY_QUESTIONS);
    }

    @Test
    void questionsAndMaterialsAreReordered() {
        String quizId = newQuiz(sponsorA(), "Order");
        String a = question(sponsorA(), quizId, "A");
        String b = question(sponsorA(), quizId, "B");
        String c = question(sponsorA(), quizId, "C");
        run("QB_QUESTION_DELETE", sponsorA(), body("quizId", quizId, "questionId", b));
        String d = question(sponsorA(), quizId, "D");
        // Deleting leaves a gap, a new question goes to the end.
        assertThat(seq("qb_question_version", "question_id", d)).isEqualTo(4);

        run("QB_QUESTION_REORDER", sponsorA(), body("quizId", quizId, "questionIds", List.of(d, a, c)));
        assertThat(List.of(seq("qb_question_version", "question_id", d), seq("qb_question_version", "question_id", a),
            seq("qb_question_version", "question_id", c))).containsExactly(1, 2, 3);
        for (List<String> wrong : List.of(List.of(d, a), List.of(d, a, c, b), List.of(d, a, a))) {
            assertThat(codes(refused("QB_QUESTION_REORDER", sponsorA(), body("quizId", quizId, "questionIds",
                wrong), 422))).containsExactly(ContentCodes.ORDER_MISMATCH);
        }

        String m1 = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE",
            "title", "1")).get("itemId");
        String m2 = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE",
            "title", "2")).get("itemId");
        run("QB_MATERIAL_REORDER", sponsorA(), body("quizId", quizId, "materialIds", List.of(m2, m1)));
        assertThat(seq("qb_material_version", "material_id", m2)).isEqualTo(1);
        run("QB_MATERIAL_DELETE", sponsorA(), body("quizId", quizId, "materialId", m1));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst()
            .get("material_count").toString()).isEqualTo("1");
    }

    @Test
    void anIncompleteDraftIsSavedButNotVersionedAndEveryProblemIsReported() {
        String quizId = newQuiz(sponsorA(), "Incomplete");
        assertThat(codes(refused("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId), 422)))
            .containsExactly(ContentCodes.NO_QUESTIONS);
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId));
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Fine", "options",
            List.of(body("text", "a", "correct", true), body("image", null))));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "LINK"));
        assertThat(violations(refused("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId), 422)))
            .extracting(v -> v.get("field"), v -> v.get("ruleCode")).containsExactly(
                tuple("questions[0].stem", ContentCodes.STEM_REQUIRED),
                tuple("questions[0].options", ContentCodes.OPTION_COUNT),
                tuple("questions[0].options", ContentCodes.NO_CORRECT),
                tuple("questions[1].options[1]", ContentCodes.OPTION_EMPTY),
                tuple("materials[0].title", ContentCodes.MATERIAL_TITLE_REQUIRED),
                tuple("materials[0].url", ContentCodes.MATERIAL_CONTENT_REQUIRED));
        assertThat(query("SELECT 1 FROM qb_quiz_version_version WHERE quiz_id = ?::uuid", quizId)).isEmpty();
    }

    @Test
    void versionsAreSnapshotsNumberedV10V11() {
        String quizId = newQuiz(sponsorA(), "Versions");
        run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Versions", "timeLimitSec", 120));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE", "title", "Read",
            "body", "Text"));
        String q1 = question(sponsorA(), quizId, "First");
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "Second", "points", 4, "options",
            List.of(body("text", "x", "correct", true), body("text", "y", "correct", true))));

        Map<String, Object> v1 = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        assertThat(v1).containsEntry("versionNo", 1).containsEntry("label", "v1.0").containsEntry("questionCount", 2)
            .containsEntry("materialCount", 1).containsEntry("fullScore", 5);
        Map<String, Object> stored = query("SELECT content, content_hash, title, time_limit_sec FROM "
            + "qb_quiz_version_version WHERE version_id = ?::uuid", v1.get("versionId")).getFirst();
        QuizSnapshot snapshot = QuizSnapshots.fromJson((String) stored.get("content"));
        assertThat(snapshot.title()).isEqualTo("Versions");
        assertThat(snapshot.timeLimitSec()).isEqualTo(120);
        assertThat(snapshot.materials()).singleElement().satisfies(m -> assertThat(m.body()).isEqualTo("Text"));
        assertThat(snapshot.questions()).extracting(QuizSnapshot.Question::no, QuizSnapshot.Question::stem,
            QuizSnapshot.Question::points).containsExactly(tuple(1, "First", 1), tuple(2, "Second", 4));
        assertThat(stored.get("content_hash")).isEqualTo(v1.get("contentHash"))
            .isEqualTo(QuizSnapshots.hashOfText((String) stored.get("content")));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("status", "VERSIONED").containsEntry("version_label", "v1.0")
            .containsEntry("changed_since_version", false);

        assertThat(codes(refused("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId), 422)))
            .containsExactly(ContentCodes.VERSION_UNCHANGED);
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "questionId", q1, "stem", "First, changed",
            "options", List.of(body("text", "yes", "correct", true), body("text", "no"))));
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .containsEntry("changed_since_version", true);
        Map<String, Object> v2 = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        assertThat(v2).containsEntry("versionNo", 2).containsEntry("label", "v1.1");

        assertThat(items("qb.sponsor.quiz-versions", sponsorA(), body("params", body("quizId", quizId))))
            .extracting(row -> row.get("label"), row -> row.get("versionNo"))
            .containsExactly(tuple("v1.1", 2), tuple("v1.0", 1));
        assertThat(items("qb.sponsor.quiz-versions", sponsorB(), body("params", body("quizId", quizId)))).isEmpty();
    }

    @Test
    void aStaleRevisionIsRefusedAndNoRevisionMeansTheLastWriterWins() {
        String quizId = newQuiz(sponsorA(), "Revisions");
        question(sponsorA(), quizId, "Q");
        assertThat(violations(refused("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "Old page",
            "baseRevision", 1), 422))).singleElement().satisfies(v -> {
                assertThat(v.get("ruleCode")).isEqualTo(ContentCodes.QUIZ_CHANGED);
                assertThat(v.get("field")).isEqualTo("baseRevision");
            });
        assertThat(codes(refused("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "x",
            "baseRevision", 1), 422))).containsExactly(ContentCodes.QUIZ_CHANGED);
        assertThat(run("QB_QUIZ_SAVE", sponsorA(), body("quizId", quizId, "title", "No revision")))
            .containsEntry("revision", 3);
    }

    @Test
    void twoQuestionsSavedAtOnceAreBothCounted() throws Exception {
        String quizId = newQuiz(sponsorA(), "Concurrent");
        var pool = Executors.newFixedThreadPool(2);
        try {
            List<CompletableFuture<Map<String, Object>>> saves = new ArrayList<>();
            for (String stem : List.of("Left", "Right")) {
                saves.add(CompletableFuture.supplyAsync(() -> run("QB_QUESTION_SAVE", sponsorA(),
                    body("quizId", quizId, "stem", stem)), pool));
            }
            for (var save : saves) {
                save.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
        }
        assertThat(latest("qb_quiz_version", "quiz_id", "quiz_id = '" + quizId + "'").getFirst())
            .satisfies(row -> assertThat(row.get("question_count").toString()).isEqualTo("2"))
            .satisfies(row -> assertThat(row.get("revision").toString()).isEqualTo("3"));
        assertThat(latest("qb_question_version", "question_id", "quiz_id = '" + quizId + "'"))
            .extracting(row -> row.get("seq").toString()).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void theSponsorsListSearchesSortsAndCounts() {
        String token = TestTokensFor.sponsor(tokens, "sponsor-list");
        String capitals = newQuiz(token, "World Capitals");
        newQuiz(token, "100% Science");
        clock.advance(Duration.ofMinutes(1));
        String rivers = newQuiz(token, "Rivers_and_lakes");
        clock.advance(Duration.ofMinutes(1));
        question(token, capitals, "Q1");
        question(token, capitals, "Q2");
        run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", capitals));

        assertThat(items("qb.sponsor.quizzes", token, body())).extracting(row -> row.get("title"))
            .containsExactly("World Capitals", "Rivers_and_lakes", "100% Science");
        assertThat(items("qb.sponsor.quizzes", token, body("params", body("q", "capitals"))))
            .singleElement().satisfies(row -> assertThat(row).containsEntry("questionCount", 2)
                .containsEntry("versionLabel", "v1.0").containsEntry("status", "VERSIONED")
                .containsEntry("changedSinceVersion", false).containsEntry("aiGenerated", false)
                .containsEntry("quizId", capitals));
        // % and _ are themselves, not wildcards.
        assertThat(items("qb.sponsor.quizzes", token, body("params", body("q", "%"))))
            .extracting(row -> row.get("title")).containsExactly("100% Science");
        assertThat(items("qb.sponsor.quizzes", token, body("params", body("q", "s_a"))))
            .extracting(row -> row.get("title")).containsExactly("Rivers_and_lakes");
        assertThat(items("qb.sponsor.quizzes", token, body("params", body("q", "_")))).extracting(row ->
            row.get("quizId")).containsExactly(rivers);
        assertThat(items("qb.sponsor.quizzes", token, body("sorts", List.of(body("field", "title", "asc", true)))))
            .extracting(row -> row.get("title")).containsExactly("100% Science", "Rivers_and_lakes", "World Capitals");
        assertThat(items("qb.sponsor.quizzes", token, body("sorts", List.of(body("field", "questionCount",
            "asc", false))))).first().satisfies(row -> assertThat(row.get("title")).isEqualTo("World Capitals"));
        assertThat(items("qb.sponsor.quizzes", token, body("filters", List.of(body("field", "status", "op", "eq",
            "value", "DRAFT"))))).hasSize(2);
        // Another sponsor's list holds none of them.
        assertThat(items("qb.sponsor.quizzes", sponsorB(), body())).extracting(row -> row.get("quizId"))
            .doesNotContain(capitals, rivers);
    }

    private static long count(String table) {
        return ((Number) query("SELECT count(*) AS n FROM " + table).getFirst().get("n")).longValue();
    }

    private static int seq(String table, String key, String id) {
        return ((Number) latest(table, key, key + " = '" + id + "'").getFirst().get("seq")).intValue();
    }

    /** Tokens of a sponsor of one's own. */
    static final class TestTokensFor {
        static String sponsor(com.jabiz.runtime.security.JwtService tokens, String actor) {
            return com.jabiz.runtime.test.TestTokens.bearer(tokens, actor,
                com.jabiz.quizbuks.QbPermissions.CONTENT_WRITE);
        }
    }
}
