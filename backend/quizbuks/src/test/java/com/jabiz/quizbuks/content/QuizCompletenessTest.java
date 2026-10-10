package com.jabiz.quizbuks.content;

import com.jabiz.entity.Violation;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class QuizCompletenessTest {

    private static QuizSnapshot withQuestions(QuizSnapshot.Question... questions) {
        return new QuizSnapshot("T", null, null, null, false, List.of(), List.of(questions));
    }

    private static QuizSnapshot withMaterials(QuizSnapshot.Material... materials) {
        return new QuizSnapshot("T", null, null, null, false, List.of(materials), Drafts.complete().questions());
    }

    private static QuizSnapshot.Material material(int no, String kind, String title, String body, String url,
        UUID pdf, UUID audio, int images) {
        List<QuizSnapshot.Image> list = new ArrayList<>();
        for (int i = 1; i <= images; i++) {
            list.add(new QuizSnapshot.Image(i, Drafts.PICTURE, null));
        }
        return new QuizSnapshot.Material(no, kind, title, null, body, url, pdf, audio, list);
    }

    private static List<String> codes(QuizSnapshot draft) {
        return QuizCompleteness.check(draft).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aCompleteDraftPasses() {
        assertThat(QuizCompleteness.check(Drafts.complete())).isEmpty();
        assertThat(QuizCompleteness.check(QuizSnapshotsTest.everyKind())).isEmpty();
    }

    @Test
    void needsAQuestion() {
        assertThat(QuizCompleteness.check(withQuestions())).singleElement()
            .satisfies(v -> assertThat(v.ruleCode()).isEqualTo(ContentCodes.NO_QUESTIONS))
            .satisfies(v -> assertThat(v.field()).isEqualTo("questions"));
    }

    @Test
    void questionsNeedTextTwoToSixOptionsAndOneCorrect() {
        QuizSnapshot.Option right = Drafts.option(1, "a", true);
        QuizSnapshot.Option wrong = Drafts.option(2, "b", false);
        assertThat(codes(withQuestions(Drafts.question(1, " ", 1, right, wrong))))
            .containsExactly(ContentCodes.STEM_REQUIRED);
        assertThat(codes(withQuestions(Drafts.question(1, "q", 1, right)))).containsExactly(ContentCodes.OPTION_COUNT);
        assertThat(codes(withQuestions(Drafts.question(1, "q", 1, right, wrong, wrong, wrong, wrong, wrong,
            wrong)))).containsExactly(ContentCodes.OPTION_COUNT);
        assertThat(codes(withQuestions(Drafts.question(1, "q", 1, right, wrong, wrong, wrong, wrong, wrong))))
            .isEmpty();
        assertThat(codes(withQuestions(Drafts.question(1, "q", 1, wrong, wrong))))
            .containsExactly(ContentCodes.NO_CORRECT);
        assertThat(codes(withQuestions(Drafts.question(1, "q", 1))))
            .containsExactly(ContentCodes.OPTION_COUNT, ContentCodes.NO_CORRECT);
    }

    @Test
    void optionsNeedTextOrAnImage() {
        QuizSnapshot draft = withQuestions(Drafts.complete().questions().getFirst(),
            Drafts.question(2, "q", 1, Drafts.option(1, "a", true), Drafts.option(2, "", false)));
        assertThat(QuizCompleteness.check(draft)).singleElement().satisfies(v -> {
            assertThat(v.ruleCode()).isEqualTo(ContentCodes.OPTION_EMPTY);
            assertThat(v.field()).isEqualTo("questions[1].options[1]");
            assertThat(v.params()).isEqualTo(Map.of("question", 2, "option", 2));
        });
        // An image is enough.
        assertThat(codes(withQuestions(new QuizSnapshot.Question(1, "q", null, 1, List.of(
            new QuizSnapshot.Option(1, null, Drafts.PICTURE, true), Drafts.option(2, "b", false)))))).isEmpty();
    }

    @Test
    void materialsNeedATitleAndContentOfTheirKind() {
        assertThat(codes(withMaterials(material(1, "ARTICLE", null, "b", null, null, null, 0))))
            .containsExactly(ContentCodes.MATERIAL_TITLE_REQUIRED);
        for (String kind : List.of("ARTICLE", "LINK", "VIDEO_LINK", "PDF", "AUDIO")) {
            assertThat(QuizCompleteness.check(withMaterials(material(1, kind, "t", null, null, null, null, 0))))
                .as(kind).singleElement().satisfies(v -> {
                    assertThat(v.ruleCode()).isEqualTo(ContentCodes.MATERIAL_CONTENT_REQUIRED);
                    assertThat(v.field()).isEqualTo("materials[0]." + MaterialKind.valueOf(kind).contentField());
                });
        }
        assertThat(codes(withMaterials(material(1, "IMAGES", "t", null, null, null, null, 0))))
            .containsExactly(ContentCodes.MATERIAL_NO_IMAGES);
        assertThat(codes(withMaterials(material(1, "IMAGES", "t", null, null, null, null, 21))))
            .containsExactly(ContentCodes.MATERIAL_NO_IMAGES);
        assertThat(codes(withMaterials(material(1, "IMAGES", "t", null, null, null, null, 20)))).isEmpty();
        // The description is not required (question Q3-3).
        assertThat(codes(withMaterials(material(1, "LINK", "t", null, "https://x.example", null, null, 0))))
            .isEmpty();
    }

    @Test
    void reportsEveryProblemAtOnce() {
        QuizSnapshot draft = new QuizSnapshot("T", null, null, null, false,
            List.of(material(1, "PDF", "", null, null, null, null, 0)),
            List.of(Drafts.question(1, null, 1, Drafts.option(1, null, false)),
                Drafts.question(2, "q", 1, Drafts.option(1, "a", true), Drafts.option(2, "b", false))));
        assertThat(QuizCompleteness.check(draft)).extracting(Violation::field, Violation::ruleCode).containsExactly(
            org.assertj.core.groups.Tuple.tuple("questions[0].stem", ContentCodes.STEM_REQUIRED),
            org.assertj.core.groups.Tuple.tuple("questions[0].options", ContentCodes.OPTION_COUNT),
            org.assertj.core.groups.Tuple.tuple("questions[0].options", ContentCodes.NO_CORRECT),
            org.assertj.core.groups.Tuple.tuple("questions[0].options[0]", ContentCodes.OPTION_EMPTY),
            org.assertj.core.groups.Tuple.tuple("materials[0].title", ContentCodes.MATERIAL_TITLE_REQUIRED),
            org.assertj.core.groups.Tuple.tuple("materials[0].pdf", ContentCodes.MATERIAL_CONTENT_REQUIRED));
    }

    /** The rules once more, written independently: the same problems, by path and code, for any draft. */
    @Property(tries = 300)
    void agreesWithAnIndependentReading(@ForAll("drafts") QuizSnapshot draft) {
        Set<String> expected = new HashSet<>();
        if (draft.questions().isEmpty()) {
            expected.add("questions " + ContentCodes.NO_QUESTIONS);
        }
        int qi = 0;
        for (QuizSnapshot.Question q : draft.questions()) {
            String p = "questions[" + qi++ + "]";
            if (q.stem() == null || q.stem().trim().isEmpty()) {
                expected.add(p + ".stem " + ContentCodes.STEM_REQUIRED);
            }
            if (!(q.options().size() >= 2 && q.options().size() <= 6)) {
                expected.add(p + ".options " + ContentCodes.OPTION_COUNT);
            }
            long correct = q.options().stream().filter(QuizSnapshot.Option::correct).count();
            if (correct == 0) {
                expected.add(p + ".options " + ContentCodes.NO_CORRECT);
            }
            int oi = 0;
            for (QuizSnapshot.Option o : q.options()) {
                if ((o.text() == null || o.text().trim().isEmpty()) && o.image() == null) {
                    expected.add(p + ".options[" + oi + "] " + ContentCodes.OPTION_EMPTY);
                }
                oi++;
            }
        }
        int mi = 0;
        for (QuizSnapshot.Material m : draft.materials()) {
            String p = "materials[" + mi++ + "]";
            if (m.title() == null || m.title().trim().isEmpty()) {
                expected.add(p + ".title " + ContentCodes.MATERIAL_TITLE_REQUIRED);
            }
            boolean ok = switch (m.kind()) {
                case "ARTICLE" -> m.body() != null && !m.body().trim().isEmpty();
                case "LINK", "VIDEO_LINK" -> m.url() != null && !m.url().trim().isEmpty();
                case "PDF" -> m.pdf() != null;
                case "AUDIO" -> m.audio() != null;
                default -> !m.images().isEmpty() && m.images().size() <= 20;
            };
            if (!ok) {
                expected.add(p + ("IMAGES".equals(m.kind()) ? ".images " + ContentCodes.MATERIAL_NO_IMAGES
                    : "." + MaterialKind.valueOf(m.kind()).contentField() + " "
                    + ContentCodes.MATERIAL_CONTENT_REQUIRED));
            }
        }
        Set<String> actual = new HashSet<>();
        QuizCompleteness.check(draft).forEach(v -> actual.add(v.field() + " " + v.ruleCode()));
        assertThat(actual).isEqualTo(expected);
    }

    @Provide
    Arbitrary<QuizSnapshot> drafts() {
        Arbitrary<String> text = Arbitraries.of(null, "", "  ", "x", "Text");
        Arbitrary<UUID> file = Arbitraries.of(null, Drafts.PICTURE);
        Arbitrary<QuizSnapshot.Option> option = Combinators.combine(text, file, Arbitraries.of(true, false))
            .as((t, f, c) -> new QuizSnapshot.Option(1, t, f, c));
        Arbitrary<QuizSnapshot.Question> question = Combinators.combine(text, option.list().ofMaxSize(7))
            .as((s, o) -> new QuizSnapshot.Question(1, s, null, 1, o));
        Arbitrary<QuizSnapshot.Material> material = Combinators.combine(
                Arbitraries.of(MaterialKind.class).map(Enum::name), text, text, text,
                Arbitraries.of(null, Drafts.PDF), Arbitraries.of(null, Drafts.AUDIO), Arbitraries.integers().between(0, 21))
            .as((k, t, b, u, p, a, n) -> material(1, k, t, b, u, p, a, n));
        return Combinators.combine(question.list().ofMaxSize(4), material.list().ofMaxSize(3))
            .as((q, m) -> new QuizSnapshot("T", null, null, null, false, m, q));
    }
}
