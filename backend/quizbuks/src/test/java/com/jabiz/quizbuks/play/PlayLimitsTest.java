package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.content.ContentLimits;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-S6: the attempt's column widths fit the largest attempt, the migration's widths (V91). */
class PlayLimitsTest {

    @Test
    void theWidthsAreTheMigrations() {
        assertThat(PlayLimits.MAX_PER_ATTEMPT_TECH).isEqualTo(200);
        assertThat(PlayLimits.ANSWERS_LENGTH).isEqualTo(3004);
        assertThat(PlayLimits.RESULT_LENGTH).isEqualTo(24004);
        assertThat(PlayLimits.QUESTION_SET_LENGTH).isEqualTo(1002);
        assertThat(ContentLimits.MAX_QUESTIONS_TECH).isGreaterThanOrEqualTo(1000);
    }

    @Test
    void theLargestQuestionSetFits() {
        // The highest numbers a bank has, four digits each.
        List<Integer> set = new ArrayList<>();
        for (int i = 0; i < PlayLimits.MAX_PER_ATTEMPT_TECH; i++) {
            set.add(ContentLimits.MAX_QUESTIONS_TECH - i);
        }
        assertThat(QuestionPick.toJson(set).length()).isLessThanOrEqualTo(PlayLimits.QUESTION_SET_LENGTH);
    }
}
