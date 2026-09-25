package com.jabiz.temporal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
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

    /** Highest version number recorded, 0 if there is none. */
    public long maxVersionNo() {
        return versions.isEmpty() ? 0 : versions.getLast().versionNo();
    }

    public Optional<EntityVersion> version(long versionNo) {
        return versions.stream().filter(v -> v.versionNo() == versionNo).findFirst();
    }
}
