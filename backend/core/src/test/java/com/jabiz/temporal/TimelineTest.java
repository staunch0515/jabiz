package com.jabiz.temporal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static com.jabiz.temporal.Versions.hour;
import static com.jabiz.temporal.Versions.inserted;
import static com.jabiz.temporal.Versions.state;
import static com.jabiz.temporal.Versions.version;
import static org.assertj.core.api.Assertions.assertThat;

class TimelineTest {

    private final EntityVersion v1 = inserted(state("name", "a", "price", 100));
    private final EntityVersion v2 = version(2, 10, 1, VersionAction.UPDATE, Set.of("price"),
        state("name", "a", "price", 200));
    /** Correction of v2: same effective time, recorded later. */
    private final EntityVersion v3 = version(3, 10, 5, VersionAction.UPDATE, Set.of("price"),
        state("name", "a", "price", 250));
    private final EntityVersion v4 = version(4, 20, 6, VersionAction.DELETE, Set.of("name", "price"),
        state("name", "a", "price", 250));

    @Test
    void theHighestNumberWinsPerEffectiveTime() {
        Timeline timeline = Timeline.of(List.of(v4, v2, v1, v3));

        assertThat(timeline.versions()).extracting(EntityVersion::versionNo).containsExactly(1L, 2L, 3L, 4L);
        assertThat(timeline.winners()).extracting(EntityVersion::versionNo).containsExactly(1L, 3L, 4L);
        assertThat(timeline.at(hour(9))).contains(v1);
        assertThat(timeline.at(hour(10))).contains(v3);
        assertThat(timeline.at(hour(25))).contains(v4);
        assertThat(timeline.at(hour(-1))).isEmpty();
        assertThat(timeline.before(hour(10))).contains(v1);
        assertThat(timeline.before(hour(0))).isEmpty();
        assertThat(timeline.effectiveExactlyAt(hour(10))).contains(v3);
        assertThat(timeline.effectiveExactlyAt(hour(11))).isEmpty();
        assertThat(timeline.after(hour(10))).containsExactly(v4);
        assertThat(timeline.after(hour(0))).containsExactly(v3, v4);
        assertThat(timeline.maxVersionNo()).isEqualTo(4);
        assertThat(timeline.version(2)).contains(v2);
        assertThat(timeline.version(9)).isEmpty();
    }

    @Test
    void knownAtHidesWhatWasRecordedLater() {
        Timeline before = Timeline.knownAt(List.of(v1, v2, v3, v4), hour(4));

        assertThat(before.at(hour(10))).contains(v2);
        assertThat(before.at(hour(30))).contains(v2);
        assertThat(Timeline.of(List.of()).maxVersionNo()).isZero();
    }

    @Test
    void theChangesAtOneEffectiveTimeAddUpUntilACancellation() {
        EntityVersion status = version(5, 10, 7, VersionAction.UPDATE, Set.of("status"),
            state("name", "a", "price", 250, "status", "DONE"));
        EntityVersion cancel = version(6, 10, 8, VersionAction.CANCEL, Set.of("price", "status"),
            state("name", "a", "price", 100));
        EntityVersion name = version(7, 10, 9, VersionAction.UPDATE, Set.of("name"), state("name", "b", "price", 100));

        assertThat(Timeline.of(List.of(v1, v2, v3, status)).changesAt(hour(10)))
            .containsExactlyInAnyOrder("price", "status");
        assertThat(Timeline.of(List.of(v1, v2, v3, status, cancel)).changesAt(hour(10))).isEmpty();
        assertThat(Timeline.of(List.of(v1, v2, v3, status, cancel, name)).changesAt(hour(10))).containsExactly("name");
        // What was recorded later does not count for an earlier point of view.
        assertThat(Timeline.knownAt(List.of(v1, v2, v3, status), hour(6)).changesAt(hour(10))).containsExactly("price");
        assertThat(Timeline.of(List.of(v1)).changesAt(hour(10))).isEmpty();
        assertThat(Timeline.of(List.of(v1)).changesAt(hour(0))).isEqualTo(v1.changedFields());
    }

    @Test
    void changesUndoneAtTheSameTimeDoNotCount() {
        EntityVersion undo = version(4, 10, 6, VersionAction.REVERT, Set.of("price"), state("name", "a", "price", 100));
        assertThat(Timeline.of(List.of(v1, v2, undo)).changesAt(hour(10))).isEmpty();
    }
}
