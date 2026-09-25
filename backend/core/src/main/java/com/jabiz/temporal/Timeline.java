package com.jabiz.temporal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The versions of one temporal entity as known at a point of recording time.
 *
 * <p>For every effective time the version with the highest number wins, so a correction (same effective time,
 * later recording) overrides what it corrects. The state at time T is the winner with the latest effective time
 * not after T, which is exactly what the {@code DISTINCT ON} query of docs/design/04-temporal-append-only.md
 * section 5.1 selects.
 */
public final class Timeline {

    private final List<EntityVersion> versions;
    /** Winning version per effective time, ordered by effective time. */
    private final TreeMap<Instant, EntityVersion> winners = new TreeMap<>();

    private Timeline(List<EntityVersion> versions, Instant knownAt) {
        this.versions = versions.stream().sorted(Comparator.comparingLong(EntityVersion::versionNo)).toList();
        for (EntityVersion version : this.versions) {
            if (knownAt == null || !version.recordedAt().isAfter(knownAt)) {
                winners.merge(version.effectiveFrom(), version,
                    (a, b) -> a.versionNo() >= b.versionNo() ? a : b);
            }
        }
    }

    /** Everything recorded so far. */
    public static Timeline of(List<EntityVersion> versions) {
        return new Timeline(versions, null);
    }

    /** What was recorded until {@code knownAt}. */
    public static Timeline knownAt(List<EntityVersion> versions, Instant knownAt) {
        return new Timeline(versions, knownAt);
    }

    /** All versions, by number. */
    public List<EntityVersion> versions() {
        return versions;
    }

    /** Winning versions ordered by effective time. */
    public List<EntityVersion> winners() {
        return List.copyOf(winners.values());
    }

    /** The version in effect at {@code time}, which may be a tombstone; empty before the entity exists. */
    public Optional<EntityVersion> at(Instant time) {
        return Optional.ofNullable(winners.floorEntry(time)).map(java.util.Map.Entry::getValue);
    }

    /** The version in effect just before {@code time}. */
    public Optional<EntityVersion> before(Instant time) {
        return Optional.ofNullable(winners.lowerEntry(time)).map(java.util.Map.Entry::getValue);
    }

    /** The winning version whose effective time is exactly {@code time}. */
    public Optional<EntityVersion> effectiveExactlyAt(Instant time) {
        return Optional.ofNullable(winners.get(time));
    }

    /** Winning versions that take effect strictly after {@code time}, in order of effective time. */
    public List<EntityVersion> after(Instant time) {
        return new ArrayList<>(winners.tailMap(time, false).values());
    }

    /**
     * Fields the versions at effective time {@code time} change, as far as a write with an earlier effective time
     * has to carry them over (decision D1, refined by D9): the fields recorded as changed by all versions at that time
     * (corrections and rebased copies build on each other; a cancellation starts over), keeping only those whose value
     * in effect at that time differs from the value just before it. Changes that were undone at the same time, by a
     * revert for example, therefore no longer count. Only versions visible in this timeline count.
     */
    public Set<String> changesAt(Instant time) {
        EntityVersion winner = winners.get(time);
        if (winner == null) {
            return Set.of();
        }
        Set<String> recorded = new LinkedHashSet<>();
        for (EntityVersion version : versions) {
            if (!version.effectiveFrom().equals(time) || version.versionNo() > winner.versionNo()) {
                continue;
            }
            if (version.action() == VersionAction.CANCEL) {
                recorded.clear();
            } else {
                recorded.addAll(version.changedFields());
            }
        }
        EntityVersion previous = before(time).orElse(null);
        if (previous == null) {
            return Set.copyOf(recorded);
        }
        Set<String> changes = new LinkedHashSet<>();
        for (String field : recorded) {
            if (!sameValue(winner.state().get(field), previous.state().get(field))) {
                changes.add(field);
            }
        }
        return Set.copyOf(changes);
    }

    /**
     * Whether something takes effect at {@code time}: the entity comes into or goes out of existence, or a field
     * changes ({@link #changesAt}). False when nothing is effective exactly then, or when what was scheduled has
     * been cancelled.
     */
    public boolean hasChangeAt(Instant time) {
        EntityVersion winner = winners.get(time);
        if (winner == null) {
            return false;
        }
        boolean deletedBefore = before(time).map(EntityVersion::deleted).orElse(true);
        return winner.deleted() != deletedBefore || (!winner.deleted() && !changesAt(time).isEmpty());
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        return Objects.equals(a, b);
    }

    /** Highest version number recorded, 0 if there is none. */
    public long maxVersionNo() {
        return versions.isEmpty() ? 0 : versions.getLast().versionNo();
    }

    public Optional<EntityVersion> version(long versionNo) {
        return versions.stream().filter(v -> v.versionNo() == versionNo).findFirst();
    }
}
