package com.jabiz.quizbuks.play;

import com.jabiz.runtime.EntityInstance;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Whether the video to watch was watched before an attempt starts (docs/quizbuks/plans/Q75-video-playback.md,
 * D-Q75-4): the App reports what the player told it (seconds watched, whether the video ended), the server holds it
 * against the duration frozen on the card. The server cannot tell a true report from a made-up one (D-Q75-1): what it
 * guarantees is that an honest client does not start without watching, and that the report is kept on the attempt
 * (D-Q75-9) for the sponsor's figures and the abuse checks to come. The App's {@code video/playback.ts} applies the
 * same rule (its test has the same cases as {@code WatchRulesTest}).
 */
public final class WatchRules {

    /** The App could read the player: the report says what was watched. */
    public static final String REPORTED = "REPORTED";
    /** The player gave the App no events (an extension, a private window, a restricted video): let through (D-Q75-6). */
    public static final String UNKNOWN = "UNKNOWN";

    /** The share of the duration that counts as watched: the credits are often skipped (D-Q75-4). */
    public static final BigDecimal WATCHED_SHARE = new BigDecimal("0.9");
    public static final int MAX_WATCHED_SEC = 99_999;

    /**
     * What the App reports of the playback when starting; every component optional (an App from before Q75 sends
     * none). {@code status} absent with figures given reads as {@link #REPORTED}.
     *
     * @param watchedSec          seconds watched at normal pace (the App counts time only while playing, at most twice
     *                            the wall clock: skipping ahead is not watching)
     * @param ended               whether the player reported the end of the video
     * @param reportedDurationSec the duration the player told the App; kept beside the sponsor's for comparison, never
     *                            used instead of it
     */
    public record Playback(@Pattern(regexp = "REPORTED|UNKNOWN") String status,
        @Min(0) @Max(MAX_WATCHED_SEC) Integer watchedSec, Boolean ended,
        @Min(0) @Max(MAX_WATCHED_SEC) Integer reportedDurationSec) {

        public String effectiveStatus() {
            if (status != null) {
                return status;
            }
            return watchedSec != null || ended != null ? REPORTED : null;
        }
    }

    private WatchRules() {}

    /** Whether the card's video must have been watched before a new attempt (its video is the one to watch). */
    public static boolean required(EntityInstance card) {
        return card != null && Boolean.TRUE.equals(card.get("watchRequired")) && card.get("video") != null;
    }

    /** The video's duration on the card, in seconds; null for a card from before Q75 (then only its end counts). */
    public static Integer duration(EntityInstance card) {
        Object value = card == null ? null : card.get("videoDurationSec");
        return value instanceof Number n ? n.intValue() : null;
    }

    /** The seconds that count as watched: nine tenths of the duration, rounded up; null without a duration. */
    public static Integer needed(Integer durationSec) {
        if (durationSec == null) {
            return null;
        }
        return BigDecimal.valueOf(durationSec).multiply(WATCHED_SHARE).setScale(0, java.math.RoundingMode.CEILING)
            .intValueExact();
    }

    /**
     * Whether a start may go on when the watching is required (D-Q75-4, D-Q75-6): the video ended, or enough of it
     * was watched, or the player could not be read ({@link #UNKNOWN}: let through and marked). Without a duration on
     * the card only the end counts.
     */
    public static boolean satisfied(Integer durationSec, Playback playback) {
        if (playback == null) {
            return false;
        }
        if (UNKNOWN.equals(playback.effectiveStatus())) {
            return true;
        }
        if (Boolean.TRUE.equals(playback.ended())) {
            return true;
        }
        Integer needed = needed(durationSec);
        return needed != null && playback.watchedSec() != null && playback.watchedSec() >= needed;
    }

    /** How many seconds are still to watch (for the refusal's details); 0 when only the end counts or when satisfied. */
    public static int shortfallSec(Integer durationSec, Playback playback) {
        if (satisfied(durationSec, playback)) {
            return 0;
        }
        Integer needed = needed(durationSec);
        if (needed == null) {
            return 0;
        }
        int watched = playback == null || playback.watchedSec() == null ? 0 : playback.watchedSec();
        return Math.max(0, needed - watched);
    }

    /**
     * The attempt's record of the report (D-Q75-9): the three columns, all null when the card has no video (nothing
     * to report on) or the App reported nothing.
     */
    public static Map<String, Object> values(EntityInstance card, Playback playback) {
        Map<String, Object> values = new LinkedHashMap<>();
        boolean video = card != null && card.get("video") != null;
        String status = playback == null ? null : playback.effectiveStatus();
        values.put("watchedSec", video && status != null && playback.watchedSec() != null
            ? BigDecimal.valueOf(playback.watchedSec()) : null);
        values.put("videoEnded", video && status != null ? playback.ended() : null);
        values.put("playbackStatus", video ? status : null);
        return values;
    }
}
