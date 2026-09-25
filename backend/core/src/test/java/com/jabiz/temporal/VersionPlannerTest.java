package com.jabiz.temporal;

import com.jabiz.temporal.VersionPlanner.Plan;
import com.jabiz.temporal.VersionPlanner.PlannedVersion;
import com.jabiz.temporal.VersionPlanner.Write;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.jabiz.temporal.Versions.ALL;
import static com.jabiz.temporal.Versions.hour;
import static com.jabiz.temporal.Versions.inserted;
import static com.jabiz.temporal.Versions.state;
import static com.jabiz.temporal.Versions.store;
import static com.jabiz.temporal.Versions.version;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rebase (decision D1), cancellation (04 section 4.1) and revert restorations (decision D2). */
class VersionPlannerTest {

    private final EntityVersion v1 = inserted(state("name", "a", "price", 100, "status", "OPEN"));
    /** Price scheduled for hour 10. */
    private final EntityVersion scheduledPrice = version(2, 10, 1, VersionAction.UPDATE, Set.of("price"),
        state("name", "a", "price", 200, "status", "OPEN"));
    /** Status scheduled for hour 20, on top of the price. */
    private final EntityVersion scheduledStatus = version(3, 20, 2, VersionAction.UPDATE, Set.of("status"),
        state("name", "a", "price", 200, "status", "DONE"));

    private static Map<String, Object> changes(Object... keyValues) {
        Map<String, Object> changes = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            changes.put((String) keyValues[i], keyValues[i + 1]);
        }
        return changes;
    }

    @Test
    void aWriteWithoutLaterVersionsIsOneVersion() {
        Plan plan = VersionPlanner.plan(Timeline.of(List.of(v1)), Write.update(v1, hour(5), changes("name", "b")));

        assertThat(plan.hasConflicts()).isFalse();
        assertThat(plan.versions()).singleElement().satisfies(v -> {
            assertThat(v.versionNo()).isEqualTo(2);
            assertThat(v.effectiveFrom()).isEqualTo(hour(5));
            assertThat(v.state()).containsEntry("name", "b").containsEntry("price", 100);
            assertThat(v.changedFields()).containsExactly("name");
            assertThat(v.action()).isEqualTo(VersionAction.UPDATE);
            assertThat(v.baseVersionNo()).isEqualTo(1);
        });
    }

    @Test
    void laterVersionsOfOtherFieldsAreCarriedOverInOrder() {
        Timeline timeline = Timeline.of(List.of(v1, scheduledPrice, scheduledStatus));
        Plan plan = VersionPlanner.plan(timeline, Write.update(v1, hour(5), changes("name", "b")));

        assertThat(plan.versions()).extracting(PlannedVersion::versionNo, PlannedVersion::effectiveFrom,
                PlannedVersion::action, PlannedVersion::baseVersionNo)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(4L, hour(5), VersionAction.UPDATE, 1L),
                org.assertj.core.groups.Tuple.tuple(5L, hour(10), VersionAction.REBASE, 2L),
                org.assertj.core.groups.Tuple.tuple(6L, hour(20), VersionAction.REBASE, 3L));
        PlannedVersion price = plan.versions().get(1);
        assertThat(price.state()).containsEntry("name", "b").containsEntry("price", 200).containsEntry("status", "OPEN");
        assertThat(price.changedFields()).containsExactly("price");
        PlannedVersion status = plan.versions().get(2);
        assertThat(status.state()).containsEntry("name", "b").containsEntry("price", 200).containsEntry("status", "DONE");

        // Once stored, the timeline shows both changes at every later time.
        Timeline after = Timeline.of(store(timeline.versions(), plan, 3, 200));
        assertThat(after.at(hour(25)).orElseThrow().state())
            .containsEntry("name", "b").containsEntry("price", 200).containsEntry("status", "DONE");
        assertThat(after.at(hour(7)).orElseThrow().state()).containsEntry("price", 100);
    }

    @Test
    void aLaterChangeOfTheSameFieldIsAConflict() {
        Timeline timeline = Timeline.of(List.of(v1, scheduledPrice, scheduledStatus));
        Plan plan = VersionPlanner.plan(timeline, Write.update(v1, hour(5), changes("price", 150, "status", "X")));

        assertThat(plan.versions()).isEmpty();
        assertThat(plan.conflicts()).extracting(VersionPlanner.Conflict::versionNo, VersionPlanner.Conflict::fields)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(2L, Set.of("price")),
                org.assertj.core.groups.Tuple.tuple(3L, Set.of("status")));
        assertThat(plan.conflicts().getFirst().processSeqId()).isEqualTo(102);
        assertThat(plan.conflicts().getFirst().effectiveFrom()).isEqualTo(hour(10));
    }

    @Test
    void aScheduledDeletionStaysADeletion() {
        EntityVersion scheduledDelete = version(2, 10, 1, VersionAction.DELETE, ALL, v1.state());
        Timeline timeline = Timeline.of(List.of(v1, scheduledDelete));
        Plan plan = VersionPlanner.plan(timeline, Write.update(v1, hour(5), changes("name", "b")));

        assertThat(plan.versions()).hasSize(2);
        PlannedVersion copy = plan.versions().get(1);
        assertThat(copy.deleted()).isTrue();
        assertThat(copy.action()).isEqualTo(VersionAction.REBASE);
        assertThat(copy.state()).containsEntry("name", "b");
        assertThat(copy.changedFields()).isEqualTo(ALL);
    }

    @Test
    void aDeletionCannotBeCarriedUnderLaterChanges() {
        Timeline timeline = Timeline.of(List.of(v1, scheduledPrice));
        Plan plan = VersionPlanner.plan(timeline, Write.delete(v1, hour(5), ALL));

        assertThat(plan.conflicts()).singleElement()
            .satisfies(c -> assertThat(c.fields()).containsExactly("price"));
        assertThat(VersionPlanner.plan(Timeline.of(List.of(v1)), Write.delete(v1, hour(5), ALL)).versions())
            .singleElement().satisfies(v -> {
                assertThat(v.deleted()).isTrue();
                assertThat(v.action()).isEqualTo(VersionAction.DELETE);
            });
    }

    @Test
    void aCorrectionOfThePastRebasesVersionsAlreadyInEffect() {
        // At hour 30 the price change (hour 10) is in effect; the name is corrected back to hour 5.
        Timeline timeline = Timeline.of(List.of(v1, scheduledPrice));
        Plan plan = VersionPlanner.plan(timeline, Write.update(v1, hour(5), changes("name", "fixed")));

        assertThat(plan.versions()).hasSize(2);
        assertThat(plan.versions().get(1).state()).containsEntry("name", "fixed").containsEntry("price", 200);
    }

    @Test
    void cancellingRestoresThePreviousStateAndBlocksNothing() {
        Timeline timeline = Timeline.of(List.of(v1, scheduledPrice, scheduledStatus));
        Write cancel = Write.cancel(timeline, scheduledPrice);

        assertThat(cancel.effectiveFrom()).isEqualTo(hour(10));
        assertThat(cancel.state()).containsEntry("price", 100);
        assertThat(cancel.changedFields()).containsExactly("price");
        assertThat(cancel.baseVersionNo()).isEqualTo(2);
        Plan plan = VersionPlanner.plan(timeline, cancel);
        assertThat(plan.versions()).extracting(PlannedVersion::action)
            .containsExactly(VersionAction.CANCEL, VersionAction.REBASE);
        assertThat(plan.versions().get(1).state()).containsEntry("price", 100).containsEntry("status", "DONE");

        // A later write of the price is not blocked by the cancelled schedule.
        Timeline cancelled = Timeline.of(store(timeline.versions(), plan, 3, 200));
        Plan next = VersionPlanner.plan(cancelled, Write.update(v1, hour(5), changes("price", 120)));
        assertThat(next.hasConflicts()).isFalse();
        assertThat(Timeline.of(store(cancelled.versions(), next, 4, 201)).at(hour(25)).orElseThrow().state())
            .containsEntry("price", 120).containsEntry("status", "DONE");
    }

    @Test
    void cancellingAScheduledInsertionDeletes() {
        EntityVersion scheduledInsert = version(1, 10, 0, VersionAction.INSERT, ALL, state("name", "a"));
        Write cancel = Write.cancel(Timeline.of(List.of(scheduledInsert)), scheduledInsert);
        assertThat(cancel.deleted()).isTrue();
        assertThat(cancel.state()).containsEntry("name", "a");
    }

    @Test
    void revertingRestoresTheChangedFieldsOnly() {
        EntityVersion update = version(2, 5, 1, VersionAction.UPDATE, Set.of("price"),
            state("name", "a", "price", 300, "status", "OPEN"));
        EntityVersion later = version(3, 6, 2, VersionAction.UPDATE, Set.of("name"),
            state("name", "z", "price", 300, "status", "OPEN"));
        Timeline timeline = Timeline.of(List.of(v1, update, later));

        Write revert = Write.revert(timeline, update, v1, Set.of("price"));
        assertThat(revert.effectiveFrom()).isEqualTo(hour(5));
        assertThat(revert.state()).containsEntry("price", 100).containsEntry("name", "a");
        assertThat(revert.action()).isEqualTo(VersionAction.REVERT);
        assertThat(revert.baseVersionNo()).isEqualTo(2);
        Plan plan = VersionPlanner.plan(timeline, revert);
        assertThat(plan.versions().getLast().state()).containsEntry("price", 100).containsEntry("name", "z");
    }

    @Test
    void revertingAnInsertionDeletesAndRevertingADeletionRestores() {
        Timeline inserted = Timeline.of(List.of(v1));
        Write undoInsert = Write.revert(inserted, v1, null, ALL);
        assertThat(undoInsert.deleted()).isTrue();

        EntityVersion tombstone = version(2, 5, 1, VersionAction.DELETE, ALL, v1.state());
        Timeline deleted = Timeline.of(List.of(v1, tombstone));
        Write undoDelete = Write.revert(deleted, tombstone, v1, ALL);
        assertThat(undoDelete.deleted()).isFalse();
        assertThat(undoDelete.state()).isEqualTo(v1.state());

        EntityVersion scheduledInsert = version(1, 10, 0, VersionAction.INSERT, ALL, state());
        assertThatThrownBy(() -> Write.revert(Timeline.of(List.of()), scheduledInsert, null, ALL))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anInsertionIsVersionOne() {
        Plan plan = VersionPlanner.plan(Timeline.of(List.of()), Write.insert(hour(0), state("name", "a"), ALL));
        assertThat(plan.versions()).singleElement().satisfies(v -> {
            assertThat(v.versionNo()).isEqualTo(1);
            assertThat(v.baseVersionNo()).isNull();
            assertThat(v.changedFields()).isEqualTo(ALL);
        });
    }

    /** Found by the property test: a correction at the same effective time must not hide the earlier change. */
    @Test
    void allChangesAtOneEffectiveTimeAreCarriedOver() {
        EntityVersion price = version(2, 1, 1, VersionAction.UPDATE, Set.of("price"),
            state("name", "a", "price", 1, "status", "OPEN"));
        EntityVersion status = version(3, 1, 2, VersionAction.UPDATE, Set.of("status"),
            state("name", "a", "price", 1, "status", "DONE"));
        Timeline timeline = Timeline.of(List.of(v1, price, status));

        Plan plan = VersionPlanner.plan(timeline, Write.update(v1, hour(0), changes("name", "b")));
        PlannedVersion copy = plan.versions().getLast();
        assertThat(copy.state()).containsEntry("name", "b").containsEntry("price", 1).containsEntry("status", "DONE");
        assertThat(copy.changedFields()).containsExactlyInAnyOrder("price", "status");
        assertThat(VersionPlanner.plan(timeline, Write.update(v1, hour(0), changes("price", 5))).hasConflicts())
            .isTrue();
    }
}
