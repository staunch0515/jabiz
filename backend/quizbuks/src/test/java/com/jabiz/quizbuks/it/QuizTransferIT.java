package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.QbContent;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Importing a quiz file (docs/quizbuks/plans/Q16-quiz-transfer.md): the check says which version reads the file and
 * which files to upload, or lists every problem; the import makes the whole draft in one transaction from files the
 * caller uploaded through the content's policies, or nothing; the draft is one like any other (it saves a version).
 */
class QuizTransferIT extends ContentItSupport {

    @AfterEach
    void onlyInserted() {
        assertOnlyInserted();
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** A file of a quiz with a cover, an image material, a PDF material and two questions; no file data. */
    static Map<String, Object> document(String title) {
        return map("format", "quizbuks.quiz", "formatVersion", 1, "exportedAt", "2026-10-23T00:00:00Z",
            "quiz", map("kind", "QUIZ", "title", title, "intro", "From a file.", "timeLimitSec", 300, "cover", "c"),
            "materials", List.of(
                map("kind", "IMAGES", "title", "Maps", "images", List.of(map("image", "m", "caption", "France"))),
                map("kind", "PDF", "title", "Paper", "pdf", "p", "images", List.of())),
            "questions", List.of(
                map("stem", "Capital of France?", "image", "m", "points", 2, "options", List.of(
                    map("text", "Paris", "correct", true), map("text", "Lyon"))),
                map("stem", "Capital of Japan?", "points", 3, "options", List.of(
                    map("text", "Tokyo", "correct", true), map("text", "Osaka", "image", "m")))),
            "files", map(
                "c", map("name", "cover.jpg", "type", "image/jpeg", "size", 2000),
                "m", map("name", "image.jpg", "type", "image/jpeg", "size", 2000),
                "p", map("name", "material.pdf", "type", "application/pdf", "size", 500)));
    }

    private String image() {
        return fileId(QbContent.IMAGE_POLICY, FileSamples.jpeg(40, 30), "image.jpg", "image/jpeg");
    }

    private String pdf() {
        return fileId(QbContent.PDF_POLICY, FileSamples.pdf(), "material.pdf", "application/pdf");
    }

    private static long quizzes() {
        return ((Number) query("SELECT count(DISTINCT quiz_id) AS n FROM qb_quiz_version").getFirst().get("n"))
            .longValue();
    }

    @Test
    void theCheckSaysWhatTheFileMakesAndWhichFilesToUpload() {
        Map<String, Object> checked = run("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", document("Checked")));
        assertThat(checked).containsEntry("formatVersion", 1).containsEntry("currentVersion", 2)
            .containsEntry("readers", List.of(1, 2)).containsEntry("kind", "QUIZ").containsEntry("title", "Checked")
            .containsEntry("questions", 2).containsEntry("materials", 2);
        assertThat((List<?>) checked.get("files")).extracting(f -> ((Map<?, ?>) f).get("key") + " "
            + ((Map<?, ?>) f).get("policy")).containsExactly("c " + QbContent.IMAGE_POLICY,
            "m " + QbContent.IMAGE_POLICY, "p " + QbContent.PDF_POLICY);
    }

    @Test
    void aFileItCannotReadOrWithProblemsIsRefusedWithEveryReason() {
        Map<String, Object> newer = document("Newer");
        newer.put("formatVersion", 9);
        Map<String, Object> refusal = refused("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", newer), 422);
        assertThat(codes(refusal)).containsExactly(ContentCodes.QUIZ_FILE_UNSUPPORTED);
        assertThat(String.valueOf(refusal)).contains("9").contains("1");

        Map<String, Object> wrong = document(" ");
        wrong.put("questions", List.of(map("stem", "No points", "options", List.of(), "image", "nowhere")));
        assertThat(codes(refused("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", wrong), 422)))
            .contains(ContentCodes.QUIZ_FILE_VALUE, ContentCodes.QUIZ_FILE_MISSING);
        // Without qb.content.write (a content administrator reads, writes nothing): refused.
        assertThat(call("QB_QUIZ_IMPORT_CHECK", contentAdmin(), body("document", document("No"))).expectBody(MAP)
            .returnResult().getStatus().value()).isEqualTo(403);
    }

    @Test
    void theImportMakesTheWholeDraftFromTheUploadedFiles() {
        String cover = image();
        String map = image();
        String paper = pdf();
        Map<String, Object> out = run("QB_QUIZ_IMPORT", sponsorA(), body("document", document("Imported"), "files",
            map("c", cover, "m", map, "p", paper)));
        String quizId = (String) out.get("quizId");
        assertThat(out).containsEntry("formatVersion", 1).containsEntry("readers", List.of(1, 2));

        assertThat(query("SELECT owner_id, title, cover::text AS cover, status, question_count, material_count, "
            + "cloned_from, ai_generated FROM qb_quiz_version WHERE quiz_id = ?::uuid", quizId)).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("owner_id", "sponsor-a").containsEntry("title", "Imported")
                .containsEntry("cover", cover).containsEntry("status", "DRAFT").containsEntry("cloned_from", null)
                .containsEntry("ai_generated", false));
        assertThat(query("SELECT seq, stem, image::text AS image, points FROM qb_question_version "
            + "WHERE quiz_id = ?::uuid ORDER BY seq", quizId)).extracting(r -> r.get("stem") + " " + r.get("image"))
            .containsExactly("Capital of France? " + map, "Capital of Japan? null");
        assertThat(query("SELECT text, correct, image::text AS image FROM qb_option_version WHERE quiz_id = ?::uuid "
            + "ORDER BY question_id, seq", quizId)).hasSize(4)
            .anySatisfy(r -> assertThat(r).containsEntry("text", "Osaka").containsEntry("image", map));
        assertThat(query("SELECT kind, pdf::text AS pdf FROM qb_material_version WHERE quiz_id = ?::uuid "
            + "ORDER BY seq", quizId)).extracting(r -> r.get("kind") + " " + r.get("pdf"))
            .containsExactly("IMAGES null", "PDF " + paper);
        assertThat(query("SELECT image::text AS image FROM qb_material_image_version WHERE quiz_id = ?::uuid",
            quizId)).singleElement().satisfies(r -> assertThat(r).containsEntry("image", map));

        // A draft like any other: it saves a version.
        assertThat(run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId))).containsKey("versionId");
    }

    /** The file in Japanese, of version 2, with a Chinese translation of everything, an image and a PDF of its own. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> translated(String title) {
        Map<String, Object> document = document(title);
        document.put("formatVersion", 2);
        ((Map<String, Object>) document.get("quiz")).put("language", "ja");
        document.put("translations", map("zh", map(
            "texts", map("title", "导入的", "intro", "来自文件。", "q1.stem", "法国的首都？", "q1.o1.text", "巴黎",
                "q1.o2.text", "里昂", "q2.stem", "日本的首都？", "q2.o1.text", "东京", "q2.o2.text", "大阪"),
            "images", map("q1.image", "zm"),
            "materials", List.of(map("no", 2, "kind", "PDF", "title", "论文", "pdf", "zp", "images", List.of())),
            "hashes", map("q2.o2.text", "0000000000000000"))));
        Map<String, Object> files = new java.util.LinkedHashMap<>((Map<String, Object>) document.get("files"));
        files.put("zm", map("name", "image.jpg", "type", "image/jpeg", "size", 2000));
        files.put("zp", map("name", "material.pdf", "type", "application/pdf", "size", 500));
        document.put("files", files);
        return document;
    }

    @Test
    void aFileWithOtherLanguagesImportsThemAsTheyWere() {
        Map<String, Object> checked = run("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", translated("Both")));
        assertThat(checked).containsEntry("formatVersion", 2).containsEntry("readers", List.of(2))
            .containsEntry("language", "ja").containsEntry("languages", List.of("zh"));
        assertThat((List<?>) checked.get("files")).extracting(f -> (Object) String.valueOf(((Map<?, ?>) f).get("key"))).contains("zm", "zp");

        String zm = image();
        String zp = pdf();
        String quizId = (String) run("QB_QUIZ_IMPORT", sponsorA(), body("document", translated("Both"), "files",
            map("c", image(), "m", image(), "p", pdf(), "zm", zm, "zp", zp))).get("quizId");
        assertThat(query("SELECT language, translation_languages FROM qb_quiz_version WHERE quiz_id = ?::uuid",
            quizId).getFirst()).containsEntry("language", "ja").containsEntry("translation_languages", "zh");
        assertThat(query("SELECT count(*) AS n FROM qb_content_translation_version WHERE quiz_id = ?::uuid",
            quizId).getFirst().get("n")).isEqualTo(9L);
        assertThat(query("SELECT kind, pdf::text AS pdf FROM qb_material_version WHERE quiz_id = ?::uuid "
            + "AND language = 'zh'", quizId)).singleElement().satisfies(r -> assertThat(r).containsEntry("kind", "PDF")
            .containsEntry("pdf", zp));

        // The hash the file kept marks that option's translation out of date: the version leaves Chinese out.
        Map<String, Object> version = run("QB_QUIZ_PUBLISH_VERSION", sponsorA(), body("quizId", quizId));
        assertThat(version.get("languages")).isEqualTo(List.of("ja"));
        assertThat(String.valueOf(version.get("incomplete"))).contains("stale=[option:");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aFileWithTranslationsNamesItsBaseLanguage() {
        Map<String, Object> document = translated("No base");
        ((Map<String, Object>) document.get("quiz")).remove("language");
        assertThat(codes(refused("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", document), 422)))
            .containsExactly(ContentCodes.QUIZ_FILE_VALUE);
    }

    @Test
    void anImportWithAFileNotTheCallersOrNotUploadedForItsFieldMakesNothing() {
        long before = quizzes();
        String cover = image();
        String paper = pdf();
        String theirs = (String) upload(QbContent.IMAGE_POLICY, FileSamples.jpeg(40, 30), "b.jpg", "image/jpeg",
            sponsorB()).expectStatus().isCreated().expectBody(MAP).returnResult().getResponseBody().get("fileId");

        Map<String, Object> refusal = refused("QB_QUIZ_IMPORT", sponsorA(), body("document", document("Nothing"),
            "files", map("c", theirs, "m", paper)), 422);
        // Another sponsor's cover; a PDF where images go (twice: the material and the option); no PDF uploaded.
        assertThat(violations(refusal)).extracting(v -> v.get("field") + " " + v.get("ruleCode")).containsExactly(
            "quiz.cover " + ContentCodes.FILE_NOT_OWN,
            "materials[0].images[0].image " + ContentCodes.QUIZ_FILE_TYPE,
            "materials[1].pdf " + ContentCodes.QUIZ_FILE_NOT_UPLOADED,
            "questions[0].image " + ContentCodes.QUIZ_FILE_TYPE,
            "questions[1].options[1].image " + ContentCodes.QUIZ_FILE_TYPE);
        assertThat(quizzes()).isEqualTo(before);
        // A key given a file nobody uploaded.
        assertThat(codes(refused("QB_QUIZ_IMPORT", sponsorA(), body("document", document("Unknown"), "files",
            map("c", java.util.UUID.randomUUID().toString(), "m", cover, "p", paper)), 422)))
            .containsExactly(ContentCodes.QUIZ_FILE_NOT_UPLOADED);
        assertThat(quizzes()).isEqualTo(before);
        // A file with problems is refused by the import too, whatever was uploaded.
        assertThat(codes(refused("QB_QUIZ_IMPORT", sponsorA(), body("document", document(""), "files",
            map("c", cover, "m", cover, "p", paper)), 422))).contains(ContentCodes.QUIZ_FILE_VALUE);
        assertThat(quizzes()).isEqualTo(before);
    }

    @Test
    void aQuizAtTheContentLimitsImports() {
        // Ten articles of the longest body in three-byte characters: about 600 KB of JSON.
        List<Object> materials = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            materials.add(map("kind", "ARTICLE", "title", "Article " + i, "body", "字".repeat(20_000),
                "images", List.of()));
        }
        Map<String, Object> large = map("format", "quizbuks.quiz", "formatVersion", 1,
            "quiz", map("kind", "QUIZ", "title", "Large", "intro", "字".repeat(5_000)), "materials", materials,
            "questions", List.of(map("stem", "One?", "points", 1, "options", List.of(map("text", "Yes",
                "correct", true), map("text", "No")))));
        assertThat(run("QB_QUIZ_IMPORT_CHECK", sponsorA(), body("document", large))).containsEntry("materials", 10);
        assertThat(run("QB_QUIZ_IMPORT", sponsorA(), body("document", large))).containsKey("quizId");
    }

    @Test
    void aSurveyFileImportsAsASurvey() {
        Map<String, Object> survey = map("format", "quizbuks.quiz", "formatVersion", 1,
            "quiz", map("kind", "SURVEY", "title", "Opinions"),
            "questions", List.of(map("stem", "Which?", "points", 5, "selection", "MULTIPLE",
                "options", List.of(map("text", "A"), map("text", "B")))));
        String quizId = (String) run("QB_QUIZ_IMPORT", sponsorA(), body("document", survey)).get("quizId");
        assertThat(query("SELECT kind FROM qb_quiz_version WHERE quiz_id = ?::uuid", quizId)).singleElement()
            .satisfies(r -> assertThat(r).containsEntry("kind", "SURVEY"));
        // Worth 1 whatever the file says, as editing makes a survey's question.
        assertThat(query("SELECT selection, points FROM qb_question_version WHERE quiz_id = ?::uuid", quizId))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("selection", "MULTIPLE")
                .satisfies(row -> assertThat(((Number) row.get("points")).intValue()).isEqualTo(1)));
    }
}
