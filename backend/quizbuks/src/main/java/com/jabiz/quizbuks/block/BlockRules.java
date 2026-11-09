package com.jabiz.quizbuks.block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The rules of blocking (F-S8, docs/quizbuks/plans/impl/F-S8.md), in one place and pure Java:
 * <ul>
 *   <li>a taker blocks one of four <b>objects</b>, always from a publication's card: the publication itself
 *       ({@value #PUBLICATION}), its quiz ({@value #QUIZ}, so the quiz's later publications too), its sponsor
 *       ({@value #SPONSOR}) or the provider of the shared video it froze ({@value #PROVIDER}, F-S12);</li>
 *   <li>a publication is <b>blocked</b> for a taker when one of their blocks is one of its card's four objects
 *       ({@link #blocks}). A hard block: nothing paid (rewards, promotion, audience rules) gets past it;</li>
 *   <li>a sponsor sees only a <b>rounded count</b> of the takers blocking it ({@link #shownCount}).</li>
 * </ul>
 * The templates {@code qb.app.feed} (the home, its shelf of shorts, the vertical mode and the search),
 * {@code qb.app.publication}, {@code qb.app.inbox} and {@code qb.app.inbox-unread} write {@link #blocks} in SQL, and
 * the start of an attempt checks it ({@link BlockStart}); the comparison test {@code BlockIT} holds them together.
 * F-S6's search and F-S13's push apply the same condition.
 */
public final class BlockRules {

    public static final String PUBLICATION = "PUBLICATION";
    public static final String QUIZ = "QUIZ";
    public static final String SPONSOR = "SPONSOR";
    public static final String PROVIDER = "PROVIDER";
    public static final List<String> KINDS = List.of(PUBLICATION, QUIZ, SPONSOR, PROVIDER);

    /** The length of the user and object columns (a user's id; a publication's or quiz's is shorter). */
    public static final int ID_LENGTH = 128;
    /** The parameters' declared values ({@code QbParams}), used before the setup made them. */
    public static final int DEFAULT_MAX_PER_USER = 1000;
    public static final int DEFAULT_COUNT_MIN_GROUP = 20;
    /** The ceiling of {@code qb.block.max-per-user}, whatever the parameter says (a process reads that many rows). */
    public static final int HARD_MAX_PER_USER = 10_000;
    /** The floor of {@code qb.block.count-min-group}: a smaller group could point at a person. */
    public static final int MIN_GROUP_FLOOR = 5;
    /** A shown count is rounded down to a multiple of this. */
    public static final int ROUND_TO = 10;

    /**
     * What of a publication's card a block can name.
     *
     * @param publicationId the publication
     * @param quizId        the quiz its card froze
     * @param sponsorId     its publisher (a sponsor's user id)
     * @param providerId    the provider of the shared video its card froze; null when it has none
     */
    public record Target(String publicationId, String quizId, String sponsorId, String providerId) {}

    /**
     * One block of a taker's.
     *
     * @param kind     one of {@link #KINDS}
     * @param objectId the object
     */
    public record Block(String kind, String objectId) {}

    private BlockRules() {}

    /** The blocks that would cover a card: one for each of its objects (no provider's when it has none). */
    public static List<Block> candidates(Target target) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(new Block(PUBLICATION, target.publicationId()));
        if (target.quizId() != null) {
            blocks.add(new Block(QUIZ, target.quizId()));
        }
        if (target.sponsorId() != null) {
            blocks.add(new Block(SPONSOR, target.sponsorId()));
        }
        if (target.providerId() != null) {
            blocks.add(new Block(PROVIDER, target.providerId()));
        }
        return List.copyOf(blocks);
    }

    /** The object of a card a block of {@code kind} names; empty when the card has none (no shared video). */
    public static Optional<String> objectOf(String kind, Target target) {
        return Optional.ofNullable(switch (kind) {
            case PUBLICATION -> target.publicationId();
            case QUIZ -> target.quizId();
            case SPONSOR -> target.sponsorId();
            case PROVIDER -> target.providerId();
            default -> throw new IllegalArgumentException("No kind of block " + kind);
        });
    }

    /** Whether the taker's blocks cover the card: one of them is one of its objects. */
    public static boolean blocks(Collection<Block> blocks, Target target) {
        return !matching(blocks, target).isEmpty();
    }

    /** The taker's blocks covering the card, in the order given. */
    public static List<Block> matching(Collection<Block> blocks, Target target) {
        List<Block> candidates = candidates(target);
        return blocks.stream().filter(candidates::contains).toList();
    }

    /** Whether the caller would block something of their own: their publication, themselves, their shared video. */
    public static boolean own(String callerId, Target target) {
        return callerId != null
            && (Objects.equals(callerId, target.sponsorId()) || Objects.equals(callerId, target.providerId()));
    }

    /** {@code qb.block.max-per-user} as read (null: not there yet), within 1 and {@link #HARD_MAX_PER_USER}. */
    public static int maxPerUser(Integer parameter) {
        return Math.clamp(parameter == null ? DEFAULT_MAX_PER_USER : parameter, 1, HARD_MAX_PER_USER);
    }

    /** {@code qb.block.count-min-group} as read (null: not there yet), never below {@link #MIN_GROUP_FLOOR}. */
    public static int minGroup(Integer parameter) {
        return Math.max(MIN_GROUP_FLOOR, parameter == null ? DEFAULT_COUNT_MIN_GROUP : parameter);
    }

    /**
     * The count of blockers a sponsor is shown: empty below the minimum group ("fewer than N"), else rounded down to a
     * multiple of {@link #ROUND_TO}, never below the minimum group (7 of a group of 5 shows 5, not 0). The templates
     * write the same.
     */
    public static Optional<Long> shownCount(long blockers, int minGroup) {
        if (blockers < minGroup) {
            return Optional.empty();
        }
        return Optional.of(Math.max(minGroup, blockers / ROUND_TO * ROUND_TO));
    }
}
