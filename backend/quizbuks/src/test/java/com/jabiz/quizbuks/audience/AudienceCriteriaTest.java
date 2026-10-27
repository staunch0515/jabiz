package com.jabiz.quizbuks.audience;

import com.jabiz.entity.Violation;
import com.jabiz.quizbuks.audience.AudienceCriteria.AnswerInput;
import com.jabiz.quizbuks.audience.AudienceCriteria.ConditionInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceCriteriaTest {

    @Test
    void validConditionsHaveNoProblem() {
        assertThat(AudienceCriteria.conditionProblems(List.of(
            new ConditionInput("gender", List.of("FEMALE", "NO_ANSWER"), null, null),
            new ConditionInput("age", null, 20, 39),
            new ConditionInput("interest", List.of("FOOD", "TRAVEL"), null, null)))).isEmpty();
        assertThat(AudienceCriteria.conditionProblems(List.of())).isEmpty();
    }

    @Test
    void everyProblemOfTheConditionsAtOnceWithItsPath() {
        List<Violation> problems = AudienceCriteria.conditionProblems(List.of(
            new ConditionInput("religion", List.of("X"), null, null),
            new ConditionInput("gender", List.of(), null, null),
            new ConditionInput("occupation", List.of("ASTRONAUT"), null, null),
            new ConditionInput("age", null, 20, null),
            new ConditionInput("occupation", List.of("STUDENT"), null, null)));
        assertThat(problems).extracting(Violation::field, Violation::ruleCode).contains(
            org.assertj.core.groups.Tuple.tuple("criteria[0].key", AudienceCodes.UNKNOWN),
            org.assertj.core.groups.Tuple.tuple("criteria[1].options", AudienceCodes.OPTIONS_REQUIRED),
            org.assertj.core.groups.Tuple.tuple("criteria[2].options", AudienceCodes.OPTION),
            org.assertj.core.groups.Tuple.tuple("criteria[3].min", AudienceCodes.RANGE_REQUIRED),
            org.assertj.core.groups.Tuple.tuple("criteria[4].key", AudienceCodes.REPEATED));
    }

    @Test
    void aRangeStaysWithinTheCriterionLowestFirst() {
        assertThat(AudienceCriteria.conditionProblems(List.of(new ConditionInput("age", null, 12, 30))))
            .extracting(Violation::ruleCode).containsExactly(AudienceCodes.RANGE);
        assertThat(AudienceCriteria.conditionProblems(List.of(new ConditionInput("age", null, 40, 30))))
            .extracting(Violation::ruleCode).containsExactly(AudienceCodes.RANGE);
        assertThat(AudienceCriteria.conditionProblems(List.of(new ConditionInput("age", null, 13, 100)))).isEmpty();
    }

    @Test
    void validAnswersHaveNoProblem() {
        assertThat(AudienceCriteria.answerProblems(List.of(
            new AnswerInput("gender", List.of("NO_ANSWER"), null),
            new AnswerInput("age", null, 1990),
            new AnswerInput("interest", List.of("FOOD", "PETS"), null)), 2026)).isEmpty();
    }

    @Test
    void anAnswerTakesOneOptionUnlessMultipleAndABirthYearInRange() {
        List<Violation> problems = AudienceCriteria.answerProblems(List.of(
            new AnswerInput("gender", List.of("FEMALE", "MALE"), null),
            new AnswerInput("age", null, 2014),
            new AnswerInput("occupation", List.of("ASTRONAUT"), null),
            new AnswerInput("gender", List.of("FEMALE"), null)), 2026);
        assertThat(problems).extracting(Violation::field, Violation::ruleCode).contains(
            org.assertj.core.groups.Tuple.tuple("answers[0].options", AudienceCodes.ONE_OPTION),
            org.assertj.core.groups.Tuple.tuple("answers[1].number", AudienceCodes.BIRTH_YEAR),
            org.assertj.core.groups.Tuple.tuple("answers[2].options", AudienceCodes.OPTION),
            org.assertj.core.groups.Tuple.tuple("answers[3].key", AudienceCodes.REPEATED));
        assertThat(AudienceCriteria.answerProblems(List.of(new AnswerInput("age", null, 2013)), 2026)).isEmpty();
        assertThat(AudienceCriteria.answerProblems(List.of(new AnswerInput("age", null, 1899)), 2026))
            .extracting(Violation::ruleCode).containsExactly(AudienceCodes.BIRTH_YEAR);
    }

    @Test
    void theCriteriaHaveDistinctKeysAndBillingKeys() {
        assertThat(AudienceCriteria.ALL).extracting(AudienceCriterion::key).doesNotHaveDuplicates()
            .containsExactly("gender", "age", "occupation", "interest");
        assertThat(AudienceCriteria.ALL).extracting(AudienceCriterion::billingKey).doesNotHaveDuplicates();
    }
}
