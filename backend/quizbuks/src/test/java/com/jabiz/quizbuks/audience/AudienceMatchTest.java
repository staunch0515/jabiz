package com.jabiz.quizbuks.audience;

import com.jabiz.quizbuks.audience.AudienceMatch.ConditionRow;
import com.jabiz.quizbuks.audience.AudienceMatch.ProfileRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceMatchTest {

    private static final int YEAR = 2026;

    @Test
    void noConditionMatchesEverybody() {
        assertThat(AudienceMatch.matches(List.of(), List.of(), YEAR)).isTrue();
    }

    @Test
    void anyOptionOfACriterionIsEnough() {
        List<ConditionRow> conditions = List.of(new ConditionRow("gender", "FEMALE", null, null),
            new ConditionRow("gender", "OTHER", null, null));
        assertThat(AudienceMatch.matches(conditions, List.of(new ProfileRow("gender", "OTHER", null)), YEAR)).isTrue();
        assertThat(AudienceMatch.matches(conditions, List.of(new ProfileRow("gender", "MALE", null)), YEAR)).isFalse();
    }

    @Test
    void everyCriterionMustHold() {
        List<ConditionRow> conditions = List.of(new ConditionRow("gender", "FEMALE", null, null),
            new ConditionRow("interest", "FOOD", null, null));
        List<ProfileRow> both = List.of(new ProfileRow("gender", "FEMALE", null),
            new ProfileRow("interest", "TRAVEL", null), new ProfileRow("interest", "FOOD", null));
        assertThat(AudienceMatch.matches(conditions, both, YEAR)).isTrue();
        assertThat(AudienceMatch.matches(conditions, both.subList(0, 2), YEAR)).isFalse();
    }

    @Test
    void anUnansweredCriterionDoesNotHold() {
        assertThat(AudienceMatch.matches(List.of(new ConditionRow("occupation", "STUDENT", null, null)), List.of(),
            YEAR)).isFalse();
    }

    @Test
    void anAgeIsTheYearsSinceTheBirthYearWithItsBoundsIncluded() {
        List<ConditionRow> twenties = List.of(new ConditionRow("age", null, 20, 29));
        assertThat(AudienceMatch.matches(twenties, List.of(new ProfileRow("age", null, 2006)), YEAR)).isTrue();
        assertThat(AudienceMatch.matches(twenties, List.of(new ProfileRow("age", null, 1997)), YEAR)).isTrue();
        assertThat(AudienceMatch.matches(twenties, List.of(new ProfileRow("age", null, 2007)), YEAR)).isFalse();
        assertThat(AudienceMatch.matches(twenties, List.of(new ProfileRow("age", null, 1996)), YEAR)).isFalse();
        // The same birth year is a year older a year later.
        assertThat(AudienceMatch.matches(twenties, List.of(new ProfileRow("age", null, 1997)), YEAR + 1)).isFalse();
    }
}
