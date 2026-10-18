package com.jabiz.quizbuks.content.ai;

import com.jabiz.entity.Violation;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.quizbuks.setup.QbParams;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The cost limits, the settings as stored and the provider's user id (Q8a, A-7, A-9, A-11). */
class AiLimitsTest {

    private static final ZoneId TOKYO = ZoneId.of("Asia/Tokyo");
    /** 23:30 on 31 October in Tokyo. */
    private static final Instant NOW = Instant.parse("2026-10-31T14:30:00Z");
    private static final AiLimits.Settings SETTINGS = new AiLimits.Settings(20, 2_000_000, 50_000_000);

    private static List<String> codes(AiLimits.Standing standing, long estimate) {
        return AiLimits.problems(SETTINGS, standing, estimate, NOW, TOKYO).stream().map(Violation::ruleCode)
            .toList();
    }

    @Test
    void withinEveryLimitNothingIsSaid() {
        assertThat(codes(new AiLimits.Standing(1, false, 19, 1_000_000, 100_000, 10_000_000), 20_000)).isEmpty();
    }

    @Test
    void eachLimitHasItsOwnRefusalAndWhenItPasses() {
        assertThat(codes(new AiLimits.Standing(1, true, 0, 0, 0, 0), 1)).containsExactly(AiCodes.QUIZ_BUSY);
        assertThat(codes(new AiLimits.Standing(2, false, 0, 0, 0, 0), 1)).containsExactly(AiCodes.TOO_MANY_OPEN);
        List<Violation> day = AiLimits.problems(SETTINGS, new AiLimits.Standing(0, false, 20, 0, 0, 0), 1, NOW,
            TOKYO);
        assertThat(day).extracting(Violation::ruleCode).containsExactly(AiCodes.DAILY_LIMIT);
        // Midnight in Tokyo, not in UTC.
        assertThat(day.getFirst().params()).containsEntry("resetsAt", "2026-10-31T15:00:00Z");
        List<Violation> month = AiLimits.problems(SETTINGS, new AiLimits.Standing(0, false, 0, 1_990_000, 0, 0),
            10_001, NOW, TOKYO);
        assertThat(month).extracting(Violation::ruleCode).containsExactly(AiCodes.MONTHLY_LIMIT);
        assertThat(month.getFirst().params()).containsEntry("resetsAt", "2026-10-31T15:00:00Z");
        assertThat(codes(new AiLimits.Standing(0, false, 0, 0, 0, 49_999_999), 2)).containsExactly(
            AiCodes.GLOBAL_LIMIT);
    }

    @Test
    void unfinishedJobsCountAtTheirEstimate() {
        // 1.9 M used and two jobs that may still use 60 k: a third of 50 k does not fit.
        assertThat(codes(new AiLimits.Standing(1, false, 0, 1_900_000, 60_000, 0), 50_000))
            .containsExactly(AiCodes.MONTHLY_LIMIT);
        assertThat(codes(new AiLimits.Standing(1, false, 0, 1_900_000, 50_000, 0), 50_000)).isEmpty();
    }

    @Test
    void theEstimateErrsHigh() {
        // An empty request still may write every output token.
        assertThat(AiLimits.estimate(0)).isEqualTo(AiLimits.FRAMING_TOKENS + ClaudeApi.MAX_TOKENS);
        long japanese = "あ".repeat(20_000).getBytes(StandardCharsets.UTF_8).length;
        assertThat(AiLimits.estimate(japanese)).isGreaterThanOrEqualTo(20_000 + ClaudeApi.MAX_TOKENS);
    }

    @Test
    void daysAndMonthsAreTheZones() {
        assertThat(AiLimits.month(NOW, TOKYO)).isEqualTo("2026-10");
        assertThat(AiLimits.month(Instant.parse("2026-10-31T15:00:00Z"), TOKYO)).isEqualTo("2026-11");
        assertThat(AiLimits.dayStart(NOW, TOKYO)).isEqualTo(Instant.parse("2026-10-30T15:00:00Z"));
        assertThat(AiLimits.nextMonth(Instant.parse("2026-12-15T00:00:00Z"), TOKYO))
            .isEqualTo(Instant.parse("2026-12-31T15:00:00Z"));
    }

    @Test
    void settingsAreUsableOnlyWhenEveryValueIs() {
        Map<String, String> values = new HashMap<>(Map.of(QbParams.AI_ENABLED, "true", QbParams.AI_MODEL,
            "claude-opus-5-5", QbParams.AI_EFFORT, "medium", QbParams.AI_SPONSOR_DAILY_JOBS, "20",
            QbParams.AI_SPONSOR_MONTHLY_TOKENS, "2000000", QbParams.AI_GLOBAL_MONTHLY_TOKENS, "50000000",
            QbParams.AI_EXPIRE_DAYS, "7"));
        AiSettings settings = AiSettings.of(values);
        assertThat(settings.problems()).isEmpty();
        assertThat(settings.limits()).isEqualTo(SETTINGS);
        assertThat(settings.expireDays()).isEqualTo(7);
        // Off, a model of before Q8a, a missing limit: unavailable, never a guess.
        for (Map.Entry<String, String> change : List.of(Map.entry(QbParams.AI_ENABLED, "false"),
            Map.entry(QbParams.AI_MODEL, "gpt-4o-mini"), Map.entry(QbParams.AI_EFFORT, "max"),
            Map.entry(QbParams.AI_SPONSOR_MONTHLY_TOKENS, "lots"))) {
            Map<String, String> changed = new HashMap<>(values);
            changed.put(change.getKey(), change.getValue());
            assertThat(AiSettings.of(changed).problems()).as(change.toString()).extracting(Violation::ruleCode)
                .containsExactly(AiCodes.UNAVAILABLE);
        }
        Map<String, String> missing = new HashMap<>(values);
        missing.remove(QbParams.AI_GLOBAL_MONTHLY_TOKENS);
        missing.remove(QbParams.AI_EXPIRE_DAYS);
        assertThat(AiSettings.of(missing).problems()).hasSize(1);
        assertThat(AiSettings.of(missing).expireDays()).isEqualTo(AiSettings.DEFAULT_EXPIRE_DAYS);
        assertThat(AiSettings.of(Map.of()).problems()).isNotEmpty();
    }

    @Test
    void theProvidersUserIdIsAnOpaqueKeyedHash() {
        IntegrityKey key = new IntegrityKey("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        AiUserKeys keys = AiUserKeys.of(key);
        String id = keys.of("sponsor-a");
        assertThat(id).matches("qb-[0-9a-f]{32}").isEqualTo(keys.of("sponsor-a")).isNotEqualTo(keys.of("sponsor-b"))
            .doesNotContain("sponsor");
        IntegrityKey other = new IntegrityKey("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        assertThat(AiUserKeys.of(other).of("sponsor-a")).isNotEqualTo(id);
        assertThat(keys.toString()).doesNotContain("0123");
    }
}
