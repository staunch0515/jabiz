package com.jabiz.runtime;

import com.jabiz.runtime.retention.DeletionGuard;
import com.jabiz.temporal.EntityVersion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What the deletions of one temporal entity that follow each other in a commit read together before the first of them
 * (ROADMAP 17zz21; decision D46, note of 2026-11-12): the instances' versions, who refers to them now and in scheduled
 * versions, and the legal holds on the entity. Each deletion then plans and checks as it always did, row by row and in
 * order, asking this instead of the database, so that the tombstones stay held and are written together.
 *
 * <p>Kept in the Reactor context of that run of deletions only, which runs one deletion after another. It answers only
 * for the instances it read ahead, and only once for their versions: anything else (another instance, the same one
 * deleted again, a reference not read ahead, a self-reference whose referrer the run deleted before) is asked of the
 * database as before.
 */
final class DeletePrefetch {

    /** Key of the run's prefetch in the Reactor context. */
    static final Object KEY = DeletePrefetch.class;

    /** Stands for "some other instance" where only whether a target is referred to was read, not by whom. */
    static final String SOMEONE = "";

    private final String entityType;
    private final Set<String> ids;
    private final Map<UUID, List<EntityVersion>> timelines;
    /** Per incoming reference ({@link #reference}), the targets referred to (now or later) and by whom. */
    private final Map<String, Map<String, Set<String>>> referrers;
    private final DeletionGuard.Holds holds;
    private final Set<String> deleted = new HashSet<>();

    DeletePrefetch(String entityType, Map<UUID, List<EntityVersion>> timelines,
        Map<String, Map<String, Set<String>>> referrers, DeletionGuard.Holds holds) {
        this.entityType = entityType;
        this.ids = new HashSet<>();
        timelines.keySet().forEach(id -> ids.add(id.toString()));
        this.timelines = new HashMap<>(timelines);
        this.referrers = Map.copyOf(referrers);
        this.holds = holds;
    }

    /** The key of one incoming reference: the referring entity and its field. */
    static String reference(String source, String field) {
        return source + "." + field;
    }

    boolean covers(String entity) {
        return entityType.equals(entity);
    }

    /** The versions of an instance read ahead, taken once; empty when they were not (or were already taken). */
    Optional<List<EntityVersion>> takeTimeline(UUID id) {
        return Optional.ofNullable(timelines.remove(id));
    }

    /**
     * Whether the target is referred to through the reference, as the database would answer now; null when this cannot
     * tell: the target or the reference was not read ahead, or (a self-reference) one of its referrers was deleted
     * earlier in the run, which decides what refers to it now.
     */
    Boolean referenced(String reference, Object target, boolean self) {
        Map<String, Set<String>> found = referrers.get(reference);
        String key = String.valueOf(target);
        if (found == null || !ids.contains(key)) {
            return null;
        }
        Set<String> by = new HashSet<>(found.getOrDefault(key, Set.of()));
        if (self) {
            by.remove(key);
            if (by.stream().anyMatch(deleted::contains)) {
                return null;
            }
        }
        return !by.isEmpty();
    }

    DeletionGuard.Holds holds() {
        return holds;
    }

    /** Records that the run deleted the instance. */
    void deleted(Object id) {
        deleted.add(String.valueOf(id));
    }
}
