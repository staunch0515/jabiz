package com.jabiz.quizbuks.help;

import com.jabiz.param.ParamValues;
import com.jabiz.quizbuks.content.ai.AiQuestionsJob;
import com.jabiz.quizbuks.content.ai.ClaudeApi;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** F-C10: the settings as read, the charge of attempts that recorded nothing, the latest turns and the ordering. */
class HelpRulesTest {

    @Test
    void theSettingsAreTheirDeclaredValuesUntilSetAndTheSwitchIsOff() {
        HelpSettings none = HelpSettings.of(null);
        assertThat(none.enabled()).isFalse();
        assertThat(none.model()).isEqualTo("claude-haiku-5-5");
        assertThat(none.userDaily()).isEqualTo(20);
        assertThat(none.globalMonthlyTokens()).isEqualTo(20_000_000L);
        assertThat(none.historyTurns()).isEqualTo(6);
        assertThat(none.retentionDays()).isEqualTo(180);
        assertThat(none.manualMaxBytes()).isEqualTo(204_800L);
        assertThat(List.of(none.pollSeconds(), none.pollMaxMinutes(), none.remindDays(), none.autoCloseDays()))
            .containsExactly(3, 3, 2, 14);
        assertThat(List.of(none.questionMaxLength(), none.answerMaxLength(), none.maxLinks()))
            .containsExactly(2000, 8000, 5);
        HelpSettings set = HelpSettings.of(new ParamValues(Instant.EPOCH, Map.of(QbParams.HELP_ENABLED, true,
            QbParams.HELP_MODEL, "gpt-x", QbParams.HELP_HISTORY_TURNS, new BigDecimal("99"),
            QbParams.HELP_USER_DAILY, new BigDecimal("-3"), QbParams.HELP_QUESTION_MAX_LENGTH, new BigDecimal("9999"),
            QbParams.HELP_ANSWER_MAX_LENGTH, new BigDecimal("10"), QbParams.HELP_MAX_LINKS, new BigDecimal("50"))));
        assertThat(set.enabled()).isTrue();
        assertThat(set.model()).isNull();
        assertThat(set.historyTurns()).isEqualTo(HelpSettings.MAX_HISTORY_TURNS);
        assertThat(set.userDaily()).isZero();
        // Each within its bounds: the columns and the stored links' size depend on them.
        assertThat(set.questionMaxLength()).isEqualTo(QbHelp.TEXT_LENGTH);
        assertThat(set.answerMaxLength()).isEqualTo(500);
        assertThat(set.maxLinks()).isEqualTo(HelpAnswers.MAX_LINKS);
        assertThat(QbHelp.LINKS_LENGTH).isGreaterThanOrEqualTo(HelpAnswers.MAX_LINKS * (HelpAnswers.MAX_PATH + 1));
    }

    @Test
    void anAttemptThatRecordedNothingIsChargedItsReservationAndAllItsOutput() {
        Map<String, Long> recorded = Map.of(AiQuestionsJob.INPUT_TOKENS, 300L, AiQuestionsJob.OUTPUT_TOKENS, 100L);
        assertThat(HelpOutcomes.charged(recorded, 0, "claude-haiku-5-5", 1_000)).isEqualTo(new ClaudeApi.Usage(300,
            100));
        ClaudeApi.Usage two = HelpOutcomes.charged(recorded, 2, "claude-haiku-5-5", 1_000);
        assertThat(two.inputTokens()).isEqualTo(300 + 2 * 1_000);
        assertThat(two.outputTokens()).isEqualTo(100 + 2L * HelpJob.MAX_TOKENS);
        assertThat(HelpOutcomes.charged(Map.of(), 1, null, 10).inputTokens()).isEqualTo(10);
        assertThat(HelpOutcomes.percent(500, 1_000)).isEqualTo(50);
        assertThat(HelpOutcomes.percent(5_000, 1_000)).isEqualTo(500);
        assertThat(HelpOutcomes.percent(1, 0)).isEqualTo(100);
        assertThat(HelpOutcomes.percent(0, 0)).isZero();
    }

    @Test
    void theLatestTurnsAreAnsweredQuestionsOldestFirst() {
        // Newest first, as the process reads them: a failed question (no answer) between two answered ones.
        List<EntityInstance> newestFirst = List.of(message("ANSWER", "A3"), message("QUESTION", "Q3"),
            message("QUESTION", "Q2 failed"), message("ANSWER", "A1"), message("QUESTION", "Q1"));
        assertThat(HelpProcesses.history(newestFirst, 6)).containsExactly(new HelpJob.Turn("Q1", "A1"),
            new HelpJob.Turn("Q3", "A3"));
        assertThat(HelpProcesses.history(newestFirst, 1)).containsExactly(new HelpJob.Turn("Q3", "A3"));
        assertThat(HelpProcesses.history(newestFirst, 0)).isEmpty();
        assertThat(HelpProcesses.history(List.of(), 6)).isEmpty();
    }

    @Test
    void aConversationsChangesKeepTheirOrderWhenTheClockStands() {
        Instant now = Instant.parse("2026-11-09T00:00:00Z");
        assertThat(HelpProcesses.stamp(now, null)).isEqualTo(now);
        assertThat(HelpProcesses.stamp(now, thread(now.minusSeconds(1)))).isEqualTo(now);
        assertThat(HelpProcesses.stamp(now, thread(now))).isEqualTo(now.plusMillis(1));
        assertThat(QbHelp.ofReference(QbHelp.reference("t", "m"))).containsExactly("t", "m");
        assertThat(QbHelp.ofReference("help:t")).isNull();
        assertThat(QbHelp.ofReference("quiz:1")).isNull();
        assertThat(QbHelp.ofReference(null)).isNull();
    }

    @Test
    void theJobsAttemptsAndTimeoutAreConfiguredWithinTheirBounds() {
        assertThat(HelpJob.type(4, HelpJob.ATTEMPTS, HelpJob.ATTEMPT_TIMEOUT).name()).isEqualTo(HelpJob.TYPE);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HelpJob.type(4, 0, HelpJob.ATTEMPT_TIMEOUT))
            .hasMessageContaining("quizbuks.help.attempts");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HelpJob.type(4, HelpJob.MAX_ATTEMPTS + 1,
            HelpJob.ATTEMPT_TIMEOUT)).hasMessageContaining("quizbuks.help.attempts");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HelpJob.type(4, 2, java.time.Duration.ofSeconds(5)))
            .hasMessageContaining("quizbuks.help.attempt-timeout");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> HelpJob.type(4, 2, java.time.Duration.ofHours(1)))
            .hasMessageContaining("quizbuks.help.attempt-timeout");
    }

    private static EntityInstance message(String kind, String body) {
        return new EntityInstance("id-" + body, QbHelp.MESSAGE, 1, null, Map.of("kind", kind, "body", body));
    }

    private static EntityInstance thread(Instant lastActivity) {
        return new EntityInstance("t", QbHelp.THREAD, 1, null, Map.of("lastActivityAt", lastActivity));
    }
}
