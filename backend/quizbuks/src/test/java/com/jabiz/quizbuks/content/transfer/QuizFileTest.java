package com.jabiz.quizbuks.content.transfer;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentCodes;
import com.jabiz.quizbuks.content.ContentLimits;
import com.jabiz.quizbuks.content.QuizSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading, checking and converting quiz files (docs/quizbuks/plans/Q16-quiz-transfer.md). */
class QuizFileTest {

    /** A current file: a quiz with a cover, an article, an image material and two questions, one with an image. */
    static Map<String, Object> document() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "quizbuks.quiz");
        document.put("formatVersion", 1);
        document.put("quiz", map("kind", "QUIZ", "title", "Capitals", "intro", "Two **easy** ones.",
            "timeLimitSec", 300, "cover", "f1"));
        document.put("materials", List.of(
            map("kind", "ARTICLE", "title", "Read first", "body", "Paris is the capital.", "images", List.of()),
            map("kind", "IMAGES", "title", "Maps", "images", List.of(map("image", "f2", "caption", "France")))));
        document.put("questions", List.of(
            map("stem", "Capital of France?", "image", "f2", "points", 2, "options", List.of(
                map("text", "Paris", "correct", true), map("text", "Lyon", "correct", false))),
            map("stem", "Capital of Japan?", "points", 3, "options", List.of(
                map("text", "Tokyo", "correct", true), map("text", "Osaka", "image", "f3")))));
        document.put("files", map(
            "f1", map("name", "cover.jpg", "type", "image/jpeg", "size", 1000, "data", "AAAA"),
            "f2", map("name", "image.png", "type", "image/png", "size", 2000),
            "f3", map("name", "image.png", "type", "image/png", "size", 3000)));
        return document;
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    @Test
    void aCurrentFileIsReadByItsOwnVersion() {
        QuizFileFormat.Reading reading = QuizFileFormat.read(document());
        assertThat(reading.status()).isEqualTo(QuizFileFormat.Status.SUPPORTED);
        assertThat(reading.version()).isEqualTo(1);
        assertThat(reading.readers()).containsExactly(1);
        QuizFile file = reading.file();
        assertThat(file.title()).isEqualTo("Capitals");
        assertThat(file.cover()).isEqualTo("f1");
        assertThat(file.questions()).hasSize(2);
        assertThat(file.questions().get(1).options().get(1).image()).isEqualTo("f3");
        assertThat(file.files().get("f1")).isEqualTo(new QuizFile.FileEntry("cover.jpg", "image/jpeg", 1000));
        assertThat(QuizFileChecks.check(file)).isEmpty();
        // Each file once, with its field's policy; f2 is used twice.
        assertThat(QuizFileChecks.needed(file)).containsOnlyKeys("f1", "f2", "f3");
        assertThat(QuizFileChecks.uses(file)).extracting(QuizFileChecks.Use::at).containsExactly("quiz.cover",
            "materials[1].images[0].image", "questions[0].image", "questions[1].options[1].image");
    }

    @Test
    void aFileOfAnotherVersionOrFormatIsRefusedWithItsReason() {
        Map<String, Object> newer = document();
        newer.put("formatVersion", 2);
        assertThat(QuizFileFormat.read(newer).status()).isEqualTo(QuizFileFormat.Status.TOO_NEW);
        Map<String, Object> older = document();
        older.put("formatVersion", 0);
        assertThat(QuizFileFormat.read(older).status()).isEqualTo(QuizFileFormat.Status.TOO_OLD);
        Map<String, Object> other = document();
        other.put("format", "something.else");
        assertThat(QuizFileFormat.read(other).status()).isEqualTo(QuizFileFormat.Status.NOT_A_QUIZ_FILE);
        assertThat(QuizFileFormat.read(null).status()).isEqualTo(QuizFileFormat.Status.NOT_A_QUIZ_FILE);
        Map<String, Object> half = document();
        half.put("formatVersion", 1.5);
        assertThat(QuizFileFormat.read(half).status()).isEqualTo(QuizFileFormat.Status.INVALID);
        Map<String, Object> broken = document();
        broken.put("questions", "not a list");
        QuizFileFormat.Reading reading = QuizFileFormat.read(broken);
        assertThat(reading.status()).isEqualTo(QuizFileFormat.Status.INVALID);
        assertThat(reading.problem()).contains("questions");
    }

    @Test
    void anOlderVersionGoesThroughEachLaterUpgrade() {
        // As if version 3 were current: a version 1 file whose title was "name" in version 1 and the article
        // "text" until version 2.
        Map<String, Object> v1 = document();
        @SuppressWarnings("unchecked")
        Map<String, Object> quiz = new LinkedHashMap<>((Map<String, Object>) v1.get("quiz"));
        quiz.put("name", quiz.remove("title"));
        v1.put("quiz", quiz);
        List<Integer> applied = new ArrayList<>();
        UnaryOperator<Map<String, Object>> oneToTwo = doc -> {
            applied.add(1);
            @SuppressWarnings("unchecked")
            Map<String, Object> q = new LinkedHashMap<>((Map<String, Object>) doc.get("quiz"));
            q.put("title", q.remove("name"));
            Map<String, Object> next = new LinkedHashMap<>(doc);
            next.put("quiz", q);
            return next;
        };
        UnaryOperator<Map<String, Object>> twoToThree = doc -> {
            applied.add(2);
            return doc;
        };
        QuizFileFormat.Reading reading = QuizFileFormat.read(v1, 3, 1, Map.of(1, oneToTwo, 2, twoToThree));
        assertThat(reading.status()).isEqualTo(QuizFileFormat.Status.SUPPORTED);
        assertThat(reading.readers()).containsExactly(1, 2, 3);
        assertThat(applied).containsExactly(1, 2);
        assertThat(reading.file().title()).isEqualTo("Capitals");
        // A version 2 file starts at its own upgrade.
        applied.clear();
        Map<String, Object> v2 = document();
        v2.put("formatVersion", 2);
        assertThat(QuizFileFormat.read(v2, 3, 1, Map.of(1, oneToTwo, 2, twoToThree)).readers()).containsExactly(2, 3);
        assertThat(applied).containsExactly(2);
    }

    @Test
    void everyProblemIsReportedAtItsPlace() {
        Map<String, Object> document = document();
        @SuppressWarnings("unchecked")
        Map<String, Object> quiz = (Map<String, Object>) document.get("quiz");
        quiz.put("title", " ");
        quiz.put("timeLimitSec", 5);
        List<Object> questions = new ArrayList<>();
        for (int i = 0; i <= ContentLimits.MAX_QUESTIONS; i++) {
            questions.add(map("stem", "Q" + i, "points", 1, "options", List.of()));
        }
        questions.set(0, map("stem", "x".repeat(ContentLimits.STEM_LENGTH + 1), "points", 0, "selection", "SINGLE",
            "image", "missing", "options", List.of()));
        document.put("questions", questions);
        document.put("materials", List.of(map("kind", "LINK", "url", "ftp://x", "body", "not for a link"),
            map("kind", "VIDEO", "title", "?"), map("kind", "PDF", "pdf", "f1")));
        List<Violation> problems = QuizFileChecks.check(QuizFileFormat.read(document).file());
        assertThat(problems).extracting(v -> v.field() + " " + v.ruleCode()).contains(
            "quiz.title " + ContentCodes.QUIZ_FILE_VALUE,
            "quiz.timeLimitSec " + ContentCodes.QUIZ_FILE_VALUE,
            "questions " + ContentCodes.QUIZ_FILE_TOO_MANY,
            "questions[0].stem " + ContentCodes.QUIZ_FILE_VALUE,
            "questions[0].points " + ContentCodes.QUIZ_FILE_VALUE,
            "questions[0].selection " + ContentCodes.SELECTION_NOT_ALLOWED,
            "questions[0].image " + ContentCodes.QUIZ_FILE_MISSING,
            "materials[0].url " + ContentCodes.MATERIAL_URL_FORMAT,
            "materials[0].body " + ContentCodes.MATERIAL_FIELD_NOT_ALLOWED,
            "materials[1].kind " + ContentCodes.QUIZ_FILE_VALUE,
            // A JPEG is no PDF.
            "materials[2].pdf " + ContentCodes.QUIZ_FILE_TYPE);
    }

    @Test
    void aFileTooLargeForItsFieldIsRefused() {
        Map<String, Object> document = document();
        @SuppressWarnings("unchecked")
        Map<String, Object> files = (Map<String, Object>) document.get("files");
        files.put("f1", map("name", "cover.jpg", "type", "image/jpeg", "size", ContentLimits.IMAGE_MAX_BYTES + 1));
        assertThat(QuizFileChecks.check(QuizFileFormat.read(document).file()))
            .extracting(Violation::field, Violation::ruleCode)
            .containsExactly(org.assertj.core.groups.Tuple.tuple("quiz.cover", ContentCodes.QUIZ_FILE_TYPE));
    }

    @Test
    void aSurveyKeepsItsSelectionsAndHasNoCorrectOptions() {
        Map<String, Object> document = document();
        @SuppressWarnings("unchecked")
        Map<String, Object> quiz = (Map<String, Object>) document.get("quiz");
        quiz.put("kind", "SURVEY");
        document.put("questions", List.of(map("stem", "Favourite?", "points", 1, "selection", "MULTIPLE",
            "options", List.of(map("text", "A"), map("text", "B", "correct", true)))));
        QuizFile file = QuizFileFormat.read(document).file();
        assertThat(QuizFileChecks.check(file)).extracting(Violation::field)
            .containsExactly("questions[0].options[1].correct");
    }

    @Test
    void theFileBecomesADraftsContentWithItsUploadedFiles() {
        QuizFile file = QuizFileFormat.read(document()).file();
        UUID cover = UUID.randomUUID();
        UUID map = UUID.randomUUID();
        UUID osaka = UUID.randomUUID();
        QuizSnapshot snapshot = QuizTransferProcesses.snapshot(file, Map.of("f1", cover, "f2", map, "f3", osaka));
        assertThat(snapshot.title()).isEqualTo("Capitals");
        assertThat(snapshot.cover()).isEqualTo(cover);
        assertThat(snapshot.aiGenerated()).isFalse();
        assertThat(snapshot.materials()).extracting(QuizSnapshot.Material::no).containsExactly(1, 2);
        assertThat(snapshot.materials().get(1).images().getFirst().image()).isEqualTo(map);
        assertThat(snapshot.questions().get(0).image()).isEqualTo(map);
        assertThat(snapshot.questions().get(1).options()).extracting(QuizSnapshot.Option::no, QuizSnapshot.Option::image)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, null), org.assertj.core.groups.Tuple.tuple(2, osaka));
        assertThat(snapshot.fullScore()).isEqualTo(5);
    }
}
