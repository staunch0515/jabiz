package com.jabiz.quizbuks.content.ai;

import com.jabiz.quizbuks.content.ContentLanguages;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an AI translation sends and keeps (Q73d-1, D-Q73-9): the texts only, in reading order, never which options are
 * correct, the points, the images or the element keys; the missing and out-of-date texts unless all are asked for;
 * the candidates checked again as they are stored.
 */
class AiTranslateInputTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID Q1 = UUID.randomUUID();
    private static final UUID Q2 = UUID.randomUUID();
    private static final UUID O1 = UUID.randomUUID();
    private static final UUID O2 = UUID.randomUUID();
    private static final UUID O3 = UUID.randomUUID();

    private static EntityInstance row(String entity, Object id, Map<String, Object> values) {
        return new EntityInstance(id, entity, 1, null, new HashMap<>(values));
    }

    private static EntityInstance quiz() {
        return row("QbQuiz", UUID.randomUUID(), Map.of("title", "Capitals", "intro", "Two **capitals**."));
    }

    private static List<EntityInstance> questions() {
        // The second question comes first in the quiz.
        return List.of(row("QbQuestion", Q1, Map.of("stem", "Capital of France?", "seq", BigDecimal.valueOf(2),
                "points", BigDecimal.valueOf(5))),
            row("QbQuestion", Q2, Map.of("stem", "Capital of Japan?", "seq", BigDecimal.ONE,
                "points", BigDecimal.valueOf(3))));
    }

    private static List<EntityInstance> options() {
        return List.of(
            row("QbOption", O2, Map.of("questionId", Q1, "text", "Lyon", "seq", BigDecimal.valueOf(2), "correct", false)),
            row("QbOption", O1, Map.of("questionId", Q1, "text", "Paris", "seq", BigDecimal.ONE, "correct", true)),
            row("QbOption", O3, Map.of("questionId", Q2, "text", "Tokyo", "seq", BigDecimal.ONE, "correct", true)));
    }

    private static EntityInstance translation(String key, String text, String source) {
        Map<String, Object> values = new HashMap<>();
        values.put("elementKey", key);
        values.put("text", text);
        values.put("sourceHash", ContentLanguages.sourceHash(source));
        return row("QbContentTranslation", UUID.randomUUID(), values);
    }

    @Test
    void theSegmentsAreTheTextsInReadingOrderWithNothingOfTheAnswers() {
        List<AiTranslateJob.Segment> segments = AiTranslations.segments(quiz(), questions(), options(), List.of(),
            false);
        assertThat(segments).extracting(AiTranslateJob.Segment::text).containsExactly("Capitals", "Two **capitals**.",
            "Capital of Japan?", "Tokyo", "Capital of France?", "Paris", "Lyon");
        assertThat(segments).extracting(AiTranslateJob.Segment::no).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(segments).extracting(AiTranslateJob.Segment::markdown)
            .containsExactly(false, true, true, false, true, false, false);
        assertThat(segments.get(5).key()).isEqualTo(ContentLanguages.optionText(O1));
        assertThat(segments.get(5).sourceHash()).isEqualTo(ContentLanguages.sourceHash("Paris"));

        AiTranslateJob.Input input = new AiTranslateJob.Input("claude-opus-5-5", "en", "zh", "Capitals", segments,
            "user-key", 1000L);
        String sent = AiTranslatePrompt.user(input, segments, JSON);
        assertThat(sent).contains("Source language: English", "Target language: Simplified Chinese", "Paris", "Lyon")
            .doesNotContain("correct", "points", ContentLanguages.optionText(O1), O1.toString(), Q1.toString(),
                segments.getFirst().sourceHash());
    }

    @Test
    void onlyTheMissingAndOutOfDateTextsUnlessAllAreAskedFor() {
        List<EntityInstance> translations = List.of(
            translation(ContentLanguages.TITLE, "首都", "Capitals"),
            // Made from an older text of the question: out of date.
            translation(ContentLanguages.stem(Q2), "日本的首都？", "Capital of Japan"),
            translation(ContentLanguages.optionText(O3), "东京", "Tokyo"));
        assertThat(AiTranslations.segments(quiz(), questions(), options(), translations, false))
            .extracting(AiTranslateJob.Segment::text)
            .containsExactly("Two **capitals**.", "Capital of Japan?", "Capital of France?", "Paris", "Lyon");
        assertThat(AiTranslations.segments(quiz(), questions(), options(), translations, true)).hasSize(7);
    }

    @Test
    void theCandidatesAreCheckedAgainAndNumberedAsStored() {
        String hash = ContentLanguages.sourceHash("Paris");
        List<AiTranslateJob.Translated> candidates = List.of(
            new AiTranslateJob.Translated(1, ContentLanguages.TITLE, hash, "  首都 "),
            new AiTranslateJob.Translated(2, ContentLanguages.TITLE, hash, "again"),
            new AiTranslateJob.Translated(3, ContentLanguages.optionText(O1), hash, "x".repeat(301)),
            new AiTranslateJob.Translated(4, ContentLanguages.questionImage(Q1), hash, "an image"),
            new AiTranslateJob.Translated(5, ContentLanguages.optionText(O1), hash, " "),
            new AiTranslateJob.Translated(6, "nonsense", hash, "text"),
            new AiTranslateJob.Translated(7, ContentLanguages.optionText(O2), hash, "里昂"));
        AiTranslations.Checked checked = AiTranslations.check(candidates);
        assertThat(checked.invalid()).isEqualTo(5);
        assertThat(checked.valid()).extracting(AiTranslateJob.Translated::no, AiTranslateJob.Translated::text)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "首都"), org.assertj.core.groups.Tuple.tuple(2,
                "里昂"));
        assertThat(AiTranslations.fromJson(AiTranslations.toJson("zh", checked.valid()))).isEqualTo(checked.valid());
    }

    @Test
    void theBatchesKeepTheOrderAndAreAboutTheirSize() {
        List<AiTranslateJob.Segment> segments = List.of(
            new AiTranslateJob.Segment(1, "k1", "h", "a".repeat(5_000), false),
            new AiTranslateJob.Segment(2, "k2", "h", "b".repeat(2_000), false),
            new AiTranslateJob.Segment(3, "k3", "h", "c".repeat(2_000), false),
            new AiTranslateJob.Segment(4, "k4", "h", "d".repeat(9_000), false));
        assertThat(AiTranslateJob.batches(segments)).extracting(batch -> batch.stream()
            .map(AiTranslateJob.Segment::no).toList()).containsExactly(List.of(1, 2), List.of(3), List.of(4));
    }

    @Test
    void anAnswerOtherThanTheDocumentIsNone() {
        assertThat(AiTranslatePrompt.translations("not json", JSON)).isNull();
        assertThat(AiTranslatePrompt.translations("{\"other\":[]}", JSON)).isNull();
        assertThat(AiTranslatePrompt.translations("{\"translations\":[{\"no\":1,\"text\":\"一\"},{\"no\":\"2\","
            + "\"text\":\"二\"}]}", JSON)).extracting(AiTranslateJob.Translated::no).containsExactly(1);
    }
}
