package com.jabiz.quizbuks.exposure;

import java.util.Collection;
import java.util.List;

/**
 * The rules of counting exposures and of the pushes (F-S13, docs/quizbuks/plans/impl/F-S13.md), in one place and pure
 * Java. The App's {@code exposure/rules.ts} applies the same rules; both pass the cases of
 * {@code quizbuks-web/app/src/exposure/exposure-cases.json} ({@code ExposureRulesTest}, {@code exposure.test.ts}), as
 * {@code WatchRules} and the App's {@code video/playback.ts} do.
 * <ul>
 *   <li>Only a publication whose card froze <b>another's</b> shared video counts ({@link #othersVideo}): its provider
 *       is not its publisher. One's own video never counts and is never pushed.</li>
 *   <li>Whose watching counts: a signed-in taker with a verified address (the process asks for it), neither the
 *       publisher nor the provider nor the staff of either ({@link #excluded}).</li>
 *   <li>A vertical video counts in the vertical mode only, a horizontal one on the start page only ({@link #fits}); the
 *       home's shelf and its autoplay never count. Played on, without a break, for {@code qb.exposure.seconds}, or the
 *       whole of a shorter video ({@link #neededSec}).</li>
 *   <li>At most once per taker and publication; at most {@code qb.exposure.daily-cap-per-user} a taker a UTC day.</li>
 *   <li>A push (a vertical one, another's video) stands at every {@code qb.exposure.push.every}-th screen from the one
 *       the vertical mode opened on ({@link #pushAt}); once opened, never pushed to that taker again
 *       ({@link #pushable}).</li>
 *   <li>Used up when the successes reach the purchased exposures ({@link #usedUp}); what goes beyond is recorded but
 *       not charged ({@link #billable}, {@link #overshoot}); none purchased is unlimited.</li>
 * </ul>
 */
public final class ExposureRules {

    /** A push became the vertical mode's screen shown: never pushed to the taker again. */
    public static final String OPEN = "OPEN";
    /** The video was watched long enough on its surface: one exposure, if it counts. */
    public static final String EXPOSURE = "EXPOSURE";
    /** The taker went on to answer (the vertical mode's "answer", the start page's start): statistics only. */
    public static final String GO = "GO";
    public static final List<String> KINDS = List.of(OPEN, EXPOSURE, GO);

    /** The full-screen vertical mode (F-S10): vertical videos count there. */
    public static final String VERTICAL = "VERTICAL";
    /** The start page: horizontal videos count there. */
    public static final String START = "START";
    public static final List<String> SURFACES = List.of(VERTICAL, START);

    /** Timed by the player's own clock (YouTube, Vimeo, a video file: the trackable players). */
    public static final String PLAYER = "PLAYER";
    /** Timed while on the screen with the page in front (every other player). */
    public static final String SCREEN = "SCREEN";
    public static final List<String> TIMERS = List.of(PLAYER, SCREEN);

    /** What became of a report. */
    public static final String COUNTED = "COUNTED";
    public static final String RECORDED = "RECORDED";
    public static final String ALREADY = "ALREADY";
    public static final String OWN_VIDEO = "OWN_VIDEO";
    public static final String EXCLUDED = "EXCLUDED";
    public static final String NOT_LIVE = "NOT_LIVE";
    public static final String WRONG_SURFACE = "WRONG_SURFACE";
    public static final String TOO_SHORT = "TOO_SHORT";
    public static final String DAILY_CAP = "DAILY_CAP";

    /** The parameters' declared values ({@code QbParams}), used before the setup made them. */
    public static final int DEFAULT_SECONDS = 10;
    public static final int DEFAULT_DAILY_CAP = 200;
    public static final int DEFAULT_PUSH_EVERY = 5;
    public static final int DEFAULT_REPORT_BATCH = 20;
    public static final int DEFAULT_REPORT_INTERVAL_SEC = 30;

    /** The most reports one call takes. */
    public static final int MAX_BATCH = 50;
    /** The most seconds a report says (a day). */
    public static final int MAX_REPORTED_SEC = 86_400;
    public static final int MAX_SECONDS = 600;
    public static final int MAX_DAILY_CAP = 10_000;
    public static final int MAX_PUSH_EVERY = 50;
    public static final int MIN_REPORT_INTERVAL_SEC = 5;
    public static final int MAX_REPORT_INTERVAL_SEC = 600;
    /** The reconciliation's tolerance: at least this many beyond the purchased, else a twentieth of it. */
    public static final long MIN_TOLERANCE = 20;

    /**
     * What of a publication's card the rules read.
     *
     * @param publisherId the publisher (a sponsor's user id)
     * @param providerId  the provider of the shared video it froze; null without one
     * @param sharedVideo whether it froze a shared video
     * @param vertical    whether its video is vertical (F-S10)
     * @param live        whether it takes new attempts now (live, within its time)
     * @param durationSec its video's length; null when unknown
     */
    public record Card(String publisherId, String providerId, boolean sharedVideo, boolean vertical, boolean live,
        Integer durationSec) {}

    private ExposureRules() {}

    /** Whether the card's video is another's: a shared video whose provider is not the publisher. */
    public static boolean othersVideo(Card card) {
        return card.sharedVideo() && card.providerId() != null && !card.providerId().equals(card.publisherId());
    }

    /** Whether the viewer's watching never counts: the publisher, the provider, or the staff of either. */
    public static boolean excluded(String viewerId, Collection<String> staffOf, Card card) {
        if (viewerId == null) {
            return true;
        }
        if (viewerId.equals(card.publisherId()) || viewerId.equals(card.providerId())) {
            return true;
        }
        return staffOf != null && staffOf.stream().anyMatch(sponsor -> sponsor != null
            && (sponsor.equals(card.publisherId()) || sponsor.equals(card.providerId())));
    }

    /** Whether a surface counts the card's video: the vertical mode a vertical one, the start page the others. */
    public static boolean fits(String surface, boolean vertical) {
        return VERTICAL.equals(surface) ? vertical : START.equals(surface) && !vertical;
    }

    /** The seconds to watch: the parameter, or the whole video when it is shorter. */
    public static int neededSec(int seconds, Integer durationSec) {
        return durationSec != null && durationSec > 0 ? Math.min(seconds, durationSec) : seconds;
    }

    /**
     * What becomes of a report of an exposure.
     *
     * @param reportedSec    the seconds the App watched without a break
     * @param seconds        {@code qb.exposure.seconds} as read ({@link #seconds})
     * @param alreadyCounted whether this taker's exposure of the publication counted before
     * @param countedToday   how many of this taker's exposures counted this UTC day (before this one)
     * @param cap            {@code qb.exposure.daily-cap-per-user} as read ({@link #dailyCap})
     */
    public static String decide(Card card, String viewerId, Collection<String> staffOf, String surface,
        int reportedSec, int seconds, boolean alreadyCounted, int countedToday, int cap) {
        if (!othersVideo(card)) {
            return OWN_VIDEO;
        }
        if (excluded(viewerId, staffOf, card)) {
            return EXCLUDED;
        }
        if (alreadyCounted) {
            return ALREADY;
        }
        if (!card.live()) {
            return NOT_LIVE;
        }
        if (!fits(surface, card.vertical())) {
            return WRONG_SURFACE;
        }
        if (reportedSec < neededSec(seconds, card.durationSec())) {
            return TOO_SHORT;
        }
        if (countedToday >= cap) {
            return DAILY_CAP;
        }
        return COUNTED;
    }

    /** Whether the card may be pushed to a taker: another's vertical video, taking attempts, not opened by them. */
    public static boolean pushable(Card card, boolean opened) {
        return othersVideo(card) && card.vertical() && card.live() && !opened;
    }

    /**
     * Whether the screen {@code offset} screens after the one the vertical mode opened on is a push's place: every
     * {@code every}-th (the opening screen itself never).
     */
    public static boolean pushAt(int offset, int every) {
        return offset > 0 && offset % every == 0;
    }

    /** Whether the purchased exposures are used up; none purchased never is. */
    public static boolean usedUp(long successes, Long purchased) {
        return purchased != null && successes >= purchased;
    }

    /** The successes charged: all of them without a purchase, else no more than purchased. */
    public static long billable(long successes, Long purchased) {
        return purchased == null ? successes : Math.min(successes, purchased);
    }

    /** The successes beyond the purchased (concurrency): recorded, not charged. */
    public static long overshoot(long successes, Long purchased) {
        return successes - billable(successes, purchased);
    }

    /** Whether the success at {@code rank} (1 the first counted, by time) is charged. */
    public static boolean billableRank(long rank, Long purchased) {
        return purchased == null || rank <= purchased;
    }

    /** How far beyond the purchased the reconciliation lets the successes go: max(20, ⌈purchased / 20⌉). */
    public static long tolerance(Long purchased) {
        return purchased == null ? 0 : Math.max(MIN_TOLERANCE, (purchased + 19) / 20);
    }

    /** Whether the daily reconciliation finds the successes within the purchased and its tolerance. */
    public static boolean reconciles(long successes, Long purchased) {
        return purchased == null || successes <= purchased + tolerance(purchased);
    }

    /** {@code qb.exposure.seconds} as read (null: not there yet), within 1 and {@link #MAX_SECONDS}. */
    public static int seconds(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_SECONDS : parameter, 1, MAX_SECONDS);
    }

    /** {@code qb.exposure.daily-cap-per-user} as read, within 1 and {@link #MAX_DAILY_CAP}. */
    public static int dailyCap(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_DAILY_CAP : parameter, 1, MAX_DAILY_CAP);
    }

    /** {@code qb.exposure.push.every} as read, within 1 and {@link #MAX_PUSH_EVERY}. */
    public static int pushEvery(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_PUSH_EVERY : parameter, 1, MAX_PUSH_EVERY);
    }

    /** {@code qb.exposure.report.batch} as read, within 1 and {@link #MAX_BATCH}. */
    public static int reportBatch(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_REPORT_BATCH : parameter, 1, MAX_BATCH);
    }

    /** {@code qb.exposure.report.interval-sec} as read, within 5 and 600. */
    public static int reportIntervalSec(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_REPORT_INTERVAL_SEC : parameter, MIN_REPORT_INTERVAL_SEC,
            MAX_REPORT_INTERVAL_SEC);
    }
}
