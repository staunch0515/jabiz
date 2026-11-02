package com.jabiz.quizbuks.play;

import com.jabiz.quizbuks.play.WatchRules.Playback;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule of the watching (docs/quizbuks/plans/Q75-video-playback.md, D-Q75-4, D-Q75-6); the App's
 * {@code video/playback.test.ts} has the same cases.
 */
class WatchRulesTest {

    private static Playback reported(Integer watched, Boolean ended) {
        return new Playback(WatchRules.REPORTED, watched, ended, null);
    }

    @Test
    void ninetyPercentOfTheDurationRoundedUpCountsAsWatched() {
        assertThat(WatchRules.needed(100)).isEqualTo(90);
        assertThat(WatchRules.needed(95)).isEqualTo(86);
        assertThat(WatchRules.needed(1)).isEqualTo(1);
        assertThat(WatchRules.needed(null)).isNull();
    }

    @Test
    void enoughWatchedOrTheEndSatisfies() {
        assertThat(WatchRules.satisfied(100, reported(90, false))).isTrue();
        assertThat(WatchRules.satisfied(100, reported(89, false))).isFalse();
        assertThat(WatchRules.satisfied(100, reported(0, true))).isTrue();
        assertThat(WatchRules.satisfied(100, reported(null, true))).isTrue();
        assertThat(WatchRules.satisfied(100, reported(null, null))).isFalse();
        assertThat(WatchRules.satisfied(100, null)).isFalse();
        // A status left out with figures given reads as a report.
        assertThat(WatchRules.satisfied(100, new Playback(null, 95, null, null))).isTrue();
    }

    @Test
    void withoutADurationOnlyTheEndCounts() {
        assertThat(WatchRules.satisfied(null, reported(3_600, false))).isFalse();
        assertThat(WatchRules.satisfied(null, reported(3_600, true))).isTrue();
        assertThat(WatchRules.shortfallSec(null, reported(3_600, false))).isZero();
    }

    @Test
    void aPlayerThatCouldNotBeReadIsLetThrough() {
        Playback unknown = new Playback(WatchRules.UNKNOWN, null, null, null);
        assertThat(WatchRules.satisfied(100, unknown)).isTrue();
        assertThat(WatchRules.satisfied(null, unknown)).isTrue();
        assertThat(WatchRules.shortfallSec(100, unknown)).isZero();
    }

    @Test
    void theShortfallIsWhatIsStillToWatch() {
        assertThat(WatchRules.shortfallSec(100, reported(30, false))).isEqualTo(60);
        assertThat(WatchRules.shortfallSec(100, null)).isEqualTo(90);
        assertThat(WatchRules.shortfallSec(100, reported(90, false))).isZero();
    }

    @Test
    void theAttemptRecordsTheReportOnlyWhenTheCardHasAVideo() {
        var withVideo = stubCard(Map.of("video", "https://www.youtube-nocookie.com/embed/x", "watchRequired", true,
            "videoDurationSec", BigDecimal.valueOf(120)));
        var without = stubCard(Map.of("watchRequired", false));
        assertThat(WatchRules.values(withVideo, reported(100, true)))
            .containsEntry("watchedSec", BigDecimal.valueOf(100)).containsEntry("videoEnded", true)
            .containsEntry("playbackStatus", WatchRules.REPORTED);
        assertThat(WatchRules.values(withVideo, new Playback(WatchRules.UNKNOWN, null, null, null)))
            .containsEntry("watchedSec", null).containsEntry("videoEnded", null)
            .containsEntry("playbackStatus", WatchRules.UNKNOWN);
        for (Map<String, Object> none : List.of(WatchRules.values(withVideo, null),
            WatchRules.values(without, reported(100, true)))) {
            assertThat(none).hasSize(3).containsEntry("watchedSec", null).containsEntry("videoEnded", null)
                .containsEntry("playbackStatus", null);
        }
        assertThat(WatchRules.required(withVideo)).isTrue();
        assertThat(WatchRules.required(without)).isFalse();
        assertThat(WatchRules.duration(withVideo)).isEqualTo(120);
        assertThat(WatchRules.duration(without)).isNull();
    }

    /** A card as the process reads it. */
    private static com.jabiz.runtime.EntityInstance stubCard(Map<String, Object> attributes) {
        return new com.jabiz.runtime.EntityInstance("card", "QbPublicationCard", 1L, null, attributes);
    }
}
