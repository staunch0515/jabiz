package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.content.ContentLimits;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The content rules of candidate questions (Q8a): the same as a version's and the entities' lengths. */
class AiCandidatesTest {

    private static AiQuestionsJob.Option option(String text, boolean correct) {
        return new AiQuestionsJob.Option(text, correct);
    }

    private static AiQuestionsJob.Candidate good(String stem) {
        return new AiQuestionsJob.Candidate(stem, List.of(option("a", true), option("b", false)), 10, List.of(1));
    }

    private static List<String> codes(AiQuestionsJob.Candidate candidate) {
        return AiCandidates.problems(candidate, "questions[0]", 1).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aGoodCandidatePasses() {
        assertThat(AiCandidates.problems(good("Q?"), "questions[0]", 1)).isEmpty();
        // Several correct options are allowed (scoring takes them).
        assertThat(codes(new AiQuestionsJob.Candidate("Q?", List.of(option("a", true), option("b", true)), 1,
            List.of()))).isEmpty();
    }

    @Test
    void everyRuleIsReportedAtOnceWithItsPath() {
        List<Violation> problems = AiCandidates.problems(new AiQuestionsJob.Candidate(" ",
            List.of(option("", false)), 0, List.of()), "questions[2]", 3);
        assertThat(problems).extracting(Violation::ruleCode).containsExactly("QB_QUESTION_STEM_REQUIRED",
            "QB_QUESTION_OPTION_COUNT", "QB_QUESTION_NO_CORRECT", "QB_OPTION_EMPTY", "QB_QUESTION_POINTS_RANGE");
        assertThat(problems).extracting(Violation::field).containsExactly("questions[2].stem",
            "questions[2].options", "questions[2].options", "questions[2].options[0].text", "questions[2].points");
        assertThat(problems.getFirst().params()).containsEntry("question", 3);
    }

    @Test
    void lengthsAreTheColumnsInCharacters() {
        String stem = "あ".repeat(ContentLimits.STEM_LENGTH);
        assertThat(codes(good(stem))).isEmpty();
        assertThat(codes(good(stem + "あ"))).containsExactly(AiCodes.TEXT_TOO_LONG);
        AiQuestionsJob.Candidate longOption = new AiQuestionsJob.Candidate("Q?", List.of(
            option("x".repeat(ContentLimits.OPTION_LENGTH + 1), true), option("b", false)), 10, List.of());
        assertThat(codes(longOption)).containsExactly(AiCodes.TEXT_TOO_LONG);
        AiQuestionsJob.Candidate seven = new AiQuestionsJob.Candidate("Q?", List.of(option("1", true),
            option("2", false), option("3", false), option("4", false), option("5", false), option("6", false),
            option("7", false)), 10, List.of());
        assertThat(codes(seven)).containsExactly("QB_QUESTION_OPTION_COUNT");
        assertThat(codes(new AiQuestionsJob.Candidate("Q?", good("x").options(), 1001, List.of())))
            .containsExactly("QB_QUESTION_POINTS_RANGE");
    }

    @Test
    void cleaningTrimsDefaultsThePointsAndKeepsOnlyTheMaterialsSent() {
        AiQuestionsJob.Candidate cleaned = AiCandidates.clean(new AiQuestionsJob.Candidate("  Q?  ",
            List.of(option(" a ", true), option("   ", false)), null, List.of(3, 1, 9, 3)), List.of(1, 3));
        assertThat(cleaned.stem()).isEqualTo("Q?");
        assertThat(cleaned.options()).containsExactly(option("a", true), option(null, false));
        assertThat(cleaned.points()).isEqualTo(AiCandidates.DEFAULT_POINTS);
        assertThat(cleaned.materials()).containsExactly(1, 3);
    }

    @Test
    void checkKeepsTheFirstGoodOnesUpToTheCount() {
        List<AiQuestionsJob.Candidate> raw = new java.util.ArrayList<>(List.of(good("1"),
            new AiQuestionsJob.Candidate("bad", List.of(option("a", false), option("b", false)), 10, List.of()),
            good("2"), good("3")));
        raw.add(null);
        AiCandidates.Checked checked = AiCandidates.check(raw, 2, List.of(1));
        assertThat(checked.valid()).extracting(AiQuestionsJob.Candidate::stem).containsExactly("1", "2");
        // The bad one, the one beyond the count and the null.
        assertThat(checked.invalid()).isEqualTo(3);
        assertThat(AiCandidates.check(null, 5, List.of()).valid()).isEmpty();
    }
}
