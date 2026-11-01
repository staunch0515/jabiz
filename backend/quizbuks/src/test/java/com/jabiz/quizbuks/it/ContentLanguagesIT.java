package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.quizbuks.content.QuizSnapshot;
import com.jabiz.quizbuks.content.QuizSnapshots;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quiz in several languages (Q73a, docs/quizbuks/plans/Q73-content-languages.md §2): its other languages, the
 * translations of its text with their base hashes, its materials of other languages, and the versions, which keep
 * the complete languages only. The structure stays the base's.
 */
class ContentLanguagesIT extends ContentItSupport {

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    /** A quiz in Japanese with one question of two options and an article; returns its keys. */
    private record Quiz(String quizId, String questionId, List<String> optionIds, String materialId) {}

    @SuppressWarnings("unchecked")
    private Quiz quiz() {
        String quizId = (String) run("QB_QUIZ_SAVE", sponsorA(), body("title", "首都", "language", "ja",
            "intro", "首都について")).get("quizId");
        Map<String, Object> q = run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quizId, "stem", "フランスの首都は？",
            "options", List.of(body("text", "パリ", "correct", true), body("text", "リヨン"))));
        String materialId = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quizId, "kind", "ARTICLE",
            "title", "読み物", "body", "パリは……")).get("itemId");
        return new Quiz(quizId, (String) q.get("itemId"), (List<String>) q.get("partIds"), materialId);
    }

    private void languages(String quizId, String... languages) {
        run("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quizId, "languages", List.of(languages)));
    }

    /** Every text of the quiz in Chinese. */
    private void chinese(Quiz quiz) {
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "zh", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "首都"),
            body("key", ContentLanguages.INTRO, "text", "关于首都"),
            body("key", ContentLanguages.stem(quiz.questionId()), "text", "法国的首都是？"),
            body("key", ContentLanguages.optionText(quiz.optionIds().get(0)), "text", "巴黎"),
            body("key", ContentLanguages.optionText(quiz.optionIds().get(1)), "text", "里昂"))));
    }

    private List<Map<String, Object>> translations(String quizId) {
        return query("SELECT DISTINCT ON (translation_id) language, element_key, text, image, source_hash, is_deleted "
            + "FROM qb_content_translation_version WHERE quiz_id = ?::uuid "
            + "ORDER BY translation_id, version_no DESC", quizId).stream()
            .filter(row -> !Boolean.TRUE.equals(row.get("is_deleted"))).toList();
    }

    private static QuizSnapshot content(Map<String, Object> version) {
        return QuizSnapshots.fromJson((String) query("SELECT content FROM qb_quiz_version_version "
            + "WHERE version_id = ?::uuid", version.get("versionId")).getFirst().get("content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aCompleteLanguageGoesIntoTheVersionAndAnIncompleteOneIsLeftOutAndSaidSo() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh", "en");
        chinese(quiz);
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "en", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "Capitals"))));
        assertThat(translations(quiz.quizId())).hasSize(6).allSatisfy(row -> assertThat(row.get("source_hash"))
            .isNotNull());

        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId()));
        assertThat(version.get("languages")).isEqualTo(List.of("ja", "zh"));
        assertThat(version.get("incomplete")).asList().singleElement().satisfies(status -> assertThat(
            (Map<String, Object>) status).containsEntry("language", "en").extractingByKey("missing").asList()
            .hasSize(4));
        assertThat(query("SELECT languages FROM qb_quiz_version_version WHERE version_id = ?::uuid",
            version.get("versionId")).getFirst()).containsEntry("languages", "ja,zh");

        QuizSnapshot snapshot = content(version);
        assertThat(snapshot.translations()).containsOnlyKeys("zh");
        QuizSnapshot zh = QuizSnapshots.in(snapshot, "zh");
        assertThat(zh.questions().getFirst().stem()).isEqualTo("法国的首都是？");
        assertThat(zh.questions().getFirst().options()).extracting(QuizSnapshot.Option::correct)
            .containsExactly(true, false);
        // No article of its own in Chinese: the base's, which says its language is the base's.
        assertThat(zh.materials().getFirst().title()).isEqualTo("読み物");
        assertThat(zh.materials().getFirst().language()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChangedBaseMakesTheTranslationOutOfDateUntilItIsTranslatedAgain() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh");
        chinese(quiz);
        assertThat(run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId())).get("languages"))
            .isEqualTo(List.of("ja", "zh"));

        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "questionId", quiz.questionId(),
            "stem", "フランスの首都はどこ？"));
        Map<String, Object> v2 = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId()));
        assertThat(v2.get("languages")).isEqualTo(List.of("ja"));
        assertThat(v2.get("incomplete")).asList().singleElement().satisfies(status -> assertThat(
            (Map<String, Object>) status)
            .containsEntry("stale", List.of(ContentLanguages.stem(quiz.questionId()))));

        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "zh", "entries", List.of(
            body("key", ContentLanguages.stem(quiz.questionId()), "text", "法国的首都在哪里？"))));
        assertThat(run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId())).get("languages"))
            .isEqualTo(List.of("ja", "zh"));
    }

    @Test
    void aMaterialOfAnotherLanguageStandsForItsBaseThere() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh");
        chinese(quiz);
        String zhPdf = fileId(QbContent.PDF_POLICY, FileSamples.pdf(), "zh.pdf", "application/pdf");
        String pdfBase = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "PDF",
            "title", "資料", "pdf", fileId(QbContent.PDF_POLICY, FileSamples.pdf(), "ja.pdf", "application/pdf")))
            .get("itemId");
        String variant = (String) run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "PDF",
            "title", "资料", "pdf", zhPdf, "variantOf", pdfBase, "language", "zh")).get("itemId");

        // Of the base's kind, one per language, only in one of the quiz's languages, keeping what it stands for.
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "ARTICLE",
            "title", "x", "body", "x", "variantOf", pdfBase, "language", "zh"), 422)))
            .containsExactly("QB_MATERIAL_KIND_FIXED");
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "PDF",
            "title", "x", "pdf", zhPdf, "variantOf", pdfBase, "language", "zh"), 422)))
            .containsExactly("QB_MATERIAL_VARIANT_EXISTS");
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "PDF",
            "title", "x", "pdf", zhPdf, "variantOf", pdfBase, "language", "en"), 422)))
            .containsExactly("QB_TRANSLATION_LANGUAGE");
        assertThat(codes(refused("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "materialId",
            variant, "kind", "PDF", "title", "x", "pdf", zhPdf), 422))).containsExactly("QB_MATERIAL_VARIANT_FIXED");
        // A material of another language is not a material more: the quiz still has two.
        assertThat(query("SELECT material_count FROM qb_quiz_version WHERE quiz_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", quiz.quizId()).getFirst().get("material_count").toString())
            .isEqualTo("2");

        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId()));
        assertThat(version).containsEntry("materialCount", 2);
        QuizSnapshot zh = QuizSnapshots.in(content(version), "zh");
        assertThat(zh.materials()).extracting(QuizSnapshot.Material::title).containsExactly("読み物", "资料");
        assertThat(zh.materials().get(1).language()).isEqualTo("zh");
        assertThat(zh.materials().get(1).pdf().toString()).isEqualTo(zhPdf);
        // The version keeps the language's file too.
        assertThat(query("SELECT 1 FROM qb_version_file_version WHERE quiz_id = ?::uuid AND pdf = ?::uuid",
            quiz.quizId(), zhPdf)).isNotEmpty();

        // Deleting the base material takes its other languages' with it; reordering lists the base ones.
        run("QB_MATERIAL_DELETE", sponsorA(), body("quizId", quiz.quizId(), "materialId", pdfBase));
        assertThat(query("SELECT DISTINCT ON (material_id) is_deleted FROM qb_material_version "
            + "WHERE material_id = ?::uuid ORDER BY material_id, version_no DESC", variant).getFirst())
            .containsEntry("is_deleted", true);
        run("QB_MATERIAL_REORDER", sponsorA(), body("quizId", quiz.quizId(), "materialIds",
            List.of(quiz.materialId())));
    }

    @Test
    void aLanguageHasImagesOfItsOwnAndOnlyTheOwnersFiles() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh");
        String mine = fileId(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "zh.jpg", "image/jpeg");
        String theirs = (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(400, 300), "b.jpg", "image/jpeg",
            sponsorB()).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");
        assertThat(codes(refused("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "zh",
            "entries", List.of(body("key", ContentLanguages.questionImage(quiz.questionId()), "image", theirs))),
            422))).containsExactly("QB_CONTENT_FILE_NOT_OWN");
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "zh", "entries", List.of(
            body("key", ContentLanguages.questionImage(quiz.questionId()), "image", mine))));
        chinese(quiz);
        QuizSnapshot zh = QuizSnapshots.in(content(run("QB_QUIZ_PUBLISH_VERSION", sponsorA(),
            body("quizId", quiz.quizId()))), "zh");
        assertThat(String.valueOf(zh.questions().getFirst().image())).isEqualTo(mine);
    }

    @Test
    void refusesWhatIsNotATranslationOfThisQuiz() {
        Quiz quiz = quiz();
        Quiz other = quiz();
        languages(quiz.quizId(), "zh");
        assertThat(codes(refused("QB_QUIZ_LANGUAGES_SET", sponsorA(), body("quizId", quiz.quizId(),
            "languages", List.of("ja")), 422))).containsExactly("QB_QUIZ_LANGUAGE_IS_BASE");
        assertThat(codes(refused("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "en",
            "entries", List.of(body("key", ContentLanguages.TITLE, "text", "T"))), 422)))
            .containsExactly("QB_TRANSLATION_LANGUAGE");
        assertThat(codes(refused("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "zh",
            "entries", List.of(
                body("key", ContentLanguages.stem(other.questionId()), "text", "别的"),
                body("key", "quiz.cover", "text", "x"),
                body("key", ContentLanguages.TITLE, "image", other.materialId()),
                body("key", ContentLanguages.questionImage(quiz.questionId()), "text", "x"),
                body("key", ContentLanguages.optionText(quiz.optionIds().get(0)), "text", "x".repeat(301)))), 422)))
            .containsExactly("QB_TRANSLATION_KEY", "QB_TRANSLATION_KEY", "QB_TRANSLATION_FIELD",
                "QB_TRANSLATION_FIELD", "QB_TRANSLATION_TOO_LONG");
        // Another sponsor's quiz is not found.
        refused("QB_TRANSLATION_SAVE", sponsorB(), body("quizId", quiz.quizId(), "language", "zh",
            "entries", List.of(body("key", ContentLanguages.TITLE, "text", "T"))), 404);
        // The base language does not change under its translations.
        assertThat(codes(refused("QB_QUIZ_SAVE", sponsorA(), body("quizId", quiz.quizId(), "title", "首都",
            "language", "en"), 422))).containsExactly("QB_QUIZ_BASE_HAS_TRANSLATIONS");
    }

    @Test
    void deletingAnElementOrALanguageDeletesItsTranslationsAndACopyKeepsThemAsTheyAre() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh", "en");
        chinese(quiz);
        run("QB_TRANSLATION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "language", "en", "entries", List.of(
            body("key", ContentLanguages.TITLE, "text", "Capitals"))));
        run("QB_MATERIAL_SAVE", sponsorA(), body("quizId", quiz.quizId(), "kind", "ARTICLE", "title", "Reading",
            "body", "Paris is…", "variantOf", quiz.materialId(), "language", "en"));

        // An option taken out of the question takes its translation along.
        run("QB_QUESTION_SAVE", sponsorA(), body("quizId", quiz.quizId(), "questionId", quiz.questionId(),
            "stem", "フランスの首都は？", "options", List.of(body("optionId", quiz.optionIds().get(0), "text", "パリ",
                "correct", true), body("text", "マルセイユ"))));
        assertThat(translations(quiz.quizId())).extracting(row -> row.get("element_key"))
            .doesNotContain(ContentLanguages.optionText(quiz.optionIds().get(1)));

        // The copy of the draft keeps every language as it is: the Chinese lacks the new option, still.
        String copy = (String) run("QB_QUIZ_CLONE", sponsorA(), body("quizId", quiz.quizId())).get("quizId");
        assertThat(translations(copy)).hasSize(5);
        assertThat(query("SELECT translation_languages FROM qb_quiz_version WHERE quiz_id = ?::uuid "
            + "ORDER BY version_no DESC LIMIT 1", copy).getFirst()).containsEntry("translation_languages", "zh,en");
        Map<String, Object> copied = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", copy));
        assertThat(copied.get("languages")).isEqualTo(List.of("ja"));
        assertThat(QuizSnapshots.in(content(copied), "en").materials()).isNotEmpty();

        // A language taken away takes its translations and materials along.
        languages(quiz.quizId(), "zh");
        assertThat(translations(quiz.quizId())).extracting(row -> row.get("language")).containsOnly("zh");
        assertThat(query("SELECT DISTINCT ON (material_id) is_deleted FROM qb_material_version "
            + "WHERE quiz_id = ?::uuid AND language = 'en' ORDER BY material_id, version_no DESC", quiz.quizId()))
            .allSatisfy(row -> assertThat(row).containsEntry("is_deleted", true));

        // Deleting the question deletes its translations; removing the quiz deletes them all.
        run("QB_QUESTION_DELETE", sponsorA(), body("quizId", quiz.quizId(), "questionId", quiz.questionId()));
        assertThat(translations(quiz.quizId())).extracting(row -> row.get("element_key"))
            .containsOnly(ContentLanguages.TITLE, ContentLanguages.INTRO);
        run("QB_QUIZ_DELETE", sponsorA(), body("quizId", quiz.quizId()));
        assertThat(translations(quiz.quizId())).isEmpty();
    }

    @Test
    void aVersionOfACopiedVersionHashesTheSame() {
        Quiz quiz = quiz();
        languages(quiz.quizId(), "zh");
        chinese(quiz);
        Map<String, Object> v1 = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quiz.quizId()));
        String copy = (String) run("QB_QUIZ_CLONE", sponsorA(), body("quizId", quiz.quizId(), "versionNo", 1))
            .get("quizId");
        Map<String, Object> copied = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", copy));
        assertThat(copied.get("contentHash")).isEqualTo(v1.get("contentHash"));
        assertThat(copied.get("languages")).isEqualTo(List.of("ja", "zh"));
        // Unchanged since: no new version, translations included.
        assertThat(codes(refused("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", copy), 422)))
            .containsExactly("QB_QUIZ_VERSION_UNCHANGED");
    }
}
