package com.jabiz.quizbuks.videouse;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** F-S14: the rules of the ledger of shared videos' uses (docs/quizbuks/plans/impl/F-S14.md). */
class VideoUseRulesTest {

    static final LocalDate DAY = LocalDate.of(2026, 11, 9);

    static List<String> codes(VideoUseRules.Entry entry) {
        return VideoUseRules.problems(entry, VideoUseRules.DEFAULT_MAX_PERIOD_DAYS).stream().map(Violation::ruleCode).toList();
    }

    @Test
    void aUseIsOfAnothersSharedVideoOnly() {
        assertThat(VideoUseRules.othersVideo("sv", "provider", "user")).isTrue();
        assertThat(VideoUseRules.othersVideo("sv", "user", "user")).as("one's own").isFalse();
        assertThat(VideoUseRules.othersVideo(null, "provider", "user")).as("no shared video").isFalse();
        assertThat(VideoUseRules.othersVideo("sv", null, "user")).isFalse();
    }

    @Test
    void theSidesAreTheUserAndTheProvider() {
        assertThat(VideoUseRules.sideOf("u", "u", "p")).isEqualTo(QbVideoUses.SPONSOR);
        assertThat(VideoUseRules.sideOf("p", "u", "p")).isEqualTo(QbVideoUses.PROVIDER);
        assertThat(VideoUseRules.sideOf("x", "u", "p")).isNull();
        assertThat(VideoUseRules.sideOf(null, "u", "p")).isNull();
    }

    @Test
    void theStatesAndWhatEachActStartsFrom() {
        assertThat(VideoUseRules.savable(null)).as("a new one").isTrue();
        for (String status : List.of(VideoUseRules.DRAFT, VideoUseRules.REJECTED, VideoUseRules.CONFIRMED)) {
            assertThat(VideoUseRules.savable(status)).as(status).isTrue();
        }
        assertThat(VideoUseRules.savable(VideoUseRules.SUBMITTED)).isFalse();
        assertThat(VideoUseRules.afterSave()).isEqualTo(VideoUseRules.DRAFT);
        assertThat(VideoUseRules.STATUSES.stream().filter(VideoUseRules::submittable))
            .containsExactly(VideoUseRules.DRAFT);
        assertThat(VideoUseRules.STATUSES.stream().filter(VideoUseRules::answerable))
            .containsExactly(VideoUseRules.SUBMITTED);
    }

    @Test
    void thePeriodAndThePrice() {
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.FIXED, BigDecimal.ZERO))).isEmpty();
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY.minusDays(1), VideoUseRules.FIXED, BigDecimal.ONE)))
            .containsExactly(VideoUseCodes.PERIOD);
        assertThat(codes(new VideoUseRules.Entry(null, DAY, VideoUseRules.FIXED, BigDecimal.ONE)))
            .containsExactly(VideoUseCodes.PERIOD);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY.plusDays(VideoUseRules.DEFAULT_MAX_PERIOD_DAYS - 1),
            VideoUseRules.PER_MILLE, BigDecimal.ONE))).isEmpty();
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY.plusDays(VideoUseRules.DEFAULT_MAX_PERIOD_DAYS),
            VideoUseRules.PER_MILLE, BigDecimal.ONE))).containsExactly(VideoUseCodes.PERIOD);
        // The longest period is a parameter (qb.video-use.max-period-days), read within its bounds.
        assertThat(VideoUseRules.problems(new VideoUseRules.Entry(DAY, DAY.plusDays(30), VideoUseRules.FIXED,
            BigDecimal.ONE), 31)).isEmpty();
        assertThat(VideoUseRules.problems(new VideoUseRules.Entry(DAY, DAY.plusDays(31), VideoUseRules.FIXED,
            BigDecimal.ONE), 31)).extracting(Violation::ruleCode).containsExactly(VideoUseCodes.PERIOD);
        assertThat(VideoUseRules.problems(new VideoUseRules.Entry(DAY, DAY.plusDays(31), VideoUseRules.FIXED,
            BigDecimal.ONE), 31).getFirst().params()).containsEntry("maxDays", 31);
        assertThat(VideoUseRules.maxPeriodDays(null)).isEqualTo(3_660);
        assertThat(VideoUseRules.maxPeriodDays(0)).isEqualTo(1);
        assertThat(VideoUseRules.maxPeriodDays(99_999)).isEqualTo(36_600);
        assertThat(VideoUseRules.maxPeriodDays(400)).isEqualTo(400);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, "PER_VIEW", BigDecimal.ONE)))
            .containsExactly(VideoUseCodes.PRICING);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.PER_TAKER, new BigDecimal("-1"))))
            .containsExactly(VideoUseCodes.PRICE);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.PER_TAKER, new BigDecimal("1.5"))))
            .containsExactly(VideoUseCodes.PRICE);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.PER_TAKER, new BigDecimal("2.00"))))
            .as("a whole number written with decimals").isEmpty();
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.FIXED,
            VideoUseRules.MAX_PRICE.add(BigDecimal.ONE)))).containsExactly(VideoUseCodes.PRICE);
        assertThat(codes(new VideoUseRules.Entry(DAY, DAY, VideoUseRules.FIXED, null)))
            .containsExactly(VideoUseCodes.PRICE);
    }

    @Test
    void theFiguresBelowTheMinimumGroupAreNotShownNorTheAmountByThem() {
        VideoUseRules.Figures figures = new VideoUseRules.Figures(2, 2_500, 7, 1);
        assertThat(VideoUseRules.shown(4, 5)).isNull();
        assertThat(VideoUseRules.shown(5, 5)).isEqualTo(5L);
        assertThat(VideoUseRules.shown(3, 1)).as("never below 3").isEqualTo(3L);
        assertThat(VideoUseRules.shown(2, 1)).isNull();
        BigDecimal price = new BigDecimal("1500");
        assertThat(VideoUseRules.amount(VideoUseRules.FIXED, price, figures, 5)).isEqualByComparingTo("1500");
        assertThat(VideoUseRules.amount(VideoUseRules.FIXED, price, null, 5)).isEqualByComparingTo("1500");
        // 1500 per thousand of 2500: 3750.
        assertThat(VideoUseRules.amount(VideoUseRules.PER_MILLE, price, figures, 5)).isEqualByComparingTo("3750");
        // Rounded down: 7 per thousand of 2500 is 17.5.
        assertThat(VideoUseRules.amount(VideoUseRules.PER_MILLE, new BigDecimal("7"), figures, 5))
            .isEqualByComparingTo("17");
        assertThat(VideoUseRules.amount(VideoUseRules.PER_TAKER, price, figures, 5)).isEqualByComparingTo("10500");
        assertThat(VideoUseRules.amount(VideoUseRules.PER_TAKER, price, figures, 8)).as("7 takers below 8").isNull();
        assertThat(VideoUseRules.amount(VideoUseRules.PER_MILLE, price, null, 5)).isNull();
        assertThat(VideoUseRules.amount("PER_VIEW", price, figures, 5)).isNull();
        assertThat(VideoUseRules.amount(VideoUseRules.FIXED, null, figures, 5)).isNull();
        assertThat(VideoUseRules.amount(null, price, figures, 5)).isNull();
    }

    @Test
    void theReconciliationAndTheReminder() {
        assertThat(VideoUseRules.reconciles(new VideoUseRules.Figures(1, 2, 3, 4),
            new VideoUseRules.Figures(1, 2, 3, 4))).isTrue();
        assertThat(VideoUseRules.reconciles(new VideoUseRules.Figures(1, 2, 3, 4),
            new VideoUseRules.Figures(1, 2, 3, 5))).isFalse();
        assertThat(VideoUseRules.remindDays(null)).isEqualTo(VideoUseRules.DEFAULT_REMIND_DAYS);
        assertThat(VideoUseRules.remindDays(3)).isEqualTo(3);
        assertThat(VideoUseRules.remindDays(0)).isEqualTo(1);
        assertThat(VideoUseRules.remindDays(500)).isEqualTo(90);
        assertThat(VideoUseRules.reminderSource("u-1", 2)).isEqualTo("qb.video-use-remind:u-1:2");
    }
}
