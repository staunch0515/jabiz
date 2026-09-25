package com.jabiz.temporal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Builders of versions for the temporal tests; times are hours after a fixed start. */
final class Versions {

    static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    static final Set<String> ALL = Set.of("name", "price", "status");

    private Versions() {}

    static Instant hour(int hours) {
        return T0.plusSeconds(3600L * hours);
    }

    static Map<String, Object> state(Object... keyValues) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", "E1");
        for (int i = 0; i < keyValues.length; i += 2) {
            state.put((String) keyValues[i], keyValues[i + 1]);
        }
        return state;
    }

    static EntityVersion version(long no, int effectiveHour, int recordedHour, VersionAction action,
        Set<String> changed, Map<String, Object> state) {
        return new EntityVersion(no, hour(effectiveHour), hour(recordedHour), action == VersionAction.DELETE,
            state, changed, action, no == 1 ? null : no - 1, 100 + no);
    }

    static EntityVersion inserted(Map<String, Object> state) {
        return version(1, 0, 0, VersionAction.INSERT, ALL, state);
    }

    /** Appends planned versions as if they had been stored by operation {@code seq} at {@code recordedHour}. */
    static List<EntityVersion> store(List<EntityVersion> versions, VersionPlanner.Plan plan, int recordedHour,
        long seq) {
        List<EntityVersion> all = new java.util.ArrayList<>(versions);
        for (VersionPlanner.PlannedVersion p : plan.versions()) {
            all.add(new EntityVersion(p.versionNo(), p.effectiveFrom(), hour(recordedHour), p.deleted(), p.state(),
                p.changedFields(), p.action(), p.baseVersionNo(), seq));
        }
        return all;
    }
}
