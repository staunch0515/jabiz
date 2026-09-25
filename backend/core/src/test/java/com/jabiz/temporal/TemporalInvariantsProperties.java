package com.jabiz.temporal;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.jabiz.temporal.Versions.ALL;
import static com.jabiz.temporal.Versions.hour;
import static com.jabiz.temporal.Versions.state;
import static com.jabiz.temporal.Versions.store;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Invariants of the version planner under arbitrary sequences of writes with arbitrary effective times
 * (docs/design/07-quality.md section 6): numbers stay contiguous, the past before a write is untouched, the write is
 * visible from its effective time on, and every later version keeps its own changes.
 */
class TemporalInvariantsProperties {

    record Change(int effectiveHour, String field, int value) {}

    @Provide
    Arbitrary<List<Change>> changes() {
        Arbitrary<Change> change = Combinators.combine(
            Arbitraries.integers().between(0, 30),
            Arbitraries.of("name", "price", "status"),
            Arbitraries.integers().between(0, 9)).as(Change::new);
        return change.list().ofMinSize(1).ofMaxSize(25);
    }

    @Property(tries = 300)
    void writesKeepTheTimelineConsistent(@ForAll("changes") List<Change> changes) {
        List<EntityVersion> versions = new ArrayList<>(Versions.store(List.of(),
            VersionPlanner.plan(Timeline.of(List.of()),
                VersionPlanner.Write.insert(hour(0), state("name", 0, "price", 0, "status", 0), ALL)), 0, 1));
        int recorded = 1;
        for (Change change : changes) {
            Timeline before = Timeline.of(versions);
            EntityVersion base = before.at(hour(change.effectiveHour())).orElseThrow();
            if (Integer.valueOf(change.value()).equals(base.state().get(change.field()))) {
                continue;
            }
            VersionPlanner.Plan plan = VersionPlanner.plan(before, VersionPlanner.Write.update(base,
                hour(change.effectiveHour()), Map.of(change.field(), change.value())));
            if (plan.hasConflicts()) {
                // Refused only because some later version changed the same field.
                assertThat(plan.conflicts()).allMatch(c -> c.fields().contains(change.field()));
                continue;
            }
            versions = store(versions, plan, ++recorded, recorded);
            Timeline after = Timeline.of(versions);

            for (int i = 0; i < versions.size(); i++) {
                assertThat(versions.get(i).versionNo()).isEqualTo(i + 1);
            }
            for (int h = 0; h <= 31; h++) {
                Optional<EntityVersion> old = before.at(hour(h));
                Map<String, Object> now = after.at(hour(h)).orElseThrow().state();
                if (h < change.effectiveHour()) {
                    assertThat(now).isEqualTo(old.orElseThrow().state());
                } else {
                    assertThat(now.get(change.field())).isEqualTo(change.value());
                    for (String other : List.of("name", "price", "status")) {
                        if (!other.equals(change.field())) {
                            assertThat(now.get(other)).isEqualTo(old.orElseThrow().state().get(other));
                        }
                    }
                }
            }
        }
    }
}
