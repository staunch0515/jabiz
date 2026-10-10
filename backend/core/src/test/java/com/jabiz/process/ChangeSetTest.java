package com.jabiz.process;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeSetTest {

    private static final Instant LATER = Instant.parse("2026-05-01T00:00:00Z");

    /** Generates "gen-1", "gen-2" ... for entity type "Gen", knows no key for anything else. */
    private static ChangeSet changeSet() {
        int[] next = {0};
        return new ChangeSet((type, attributes) -> {
            if (attributes.containsKey("id")) {
                return attributes.get("id");
            }
            if (!type.equals("Gen")) {
                return null;
            }
            String id = "gen-" + ++next[0];
            attributes.put("id", id);
            return id;
        });
    }

    @Test
    void registersChangesInOrderWithTheirTargets() {
        ChangeSet changes = changeSet();
        Object generated = changes.insert("Gen", Map.of("name", "a"));
        Object given = changes.insert("Other", Map.of("id", "o-1"));
        Object unknown = changes.insert("Other", null);
        changes.update("Gen", generated, 1L, Map.of("name", "b"));
        changes.in("urn:ds:member").delete("Gen", "gen-9", 3L);
        changes.effectiveAt(LATER).update("Price", "p-1", 2L, Map.of("amount", 10));
        changes.in("urn:ds:admin").effectiveAt(LATER).cancelScheduled("Price", "p-1", 4L);

        assertThat(generated).isEqualTo("gen-1");
        assertThat(given).isEqualTo("o-1");
        assertThat(unknown).isNull();
        List<ChangeSet.Change> pending = changes.pending();
        assertThat(pending).extracting(ChangeSet.Change::action).containsExactly(
            ChangeSet.Action.INSERT, ChangeSet.Action.INSERT, ChangeSet.Action.INSERT, ChangeSet.Action.UPDATE,
            ChangeSet.Action.DELETE, ChangeSet.Action.UPDATE, ChangeSet.Action.CANCEL_SCHEDULED);
        assertThat(pending.getFirst().attributes()).containsEntry("id", "gen-1").containsEntry("name", "a");
        assertThat(pending).extracting(ChangeSet.Change::datasetId)
            .containsExactly(null, null, null, null, "urn:ds:member", null, "urn:ds:admin");
        assertThat(pending).extracting(ChangeSet.Change::effectiveTime)
            .containsExactly(null, null, null, null, null, LATER, LATER);
        assertThat(pending.get(4).version()).isEqualTo(3L);
        assertThat(changes.isEmpty()).isFalse();
    }

    @Test
    void aGrantGoesWithTheChangesOfItsTargetOnly() {
        ChangeSet changes = changeSet();
        Object grant = new Object();
        Instant later = Instant.parse("2026-02-01T00:00:00Z");
        ChangeSet.Target granted = changes.in("ds").granted(grant).effectiveAt(later);
        granted.update("Param", "p1", 3, Map.of("value", "1"));
        granted.cancelScheduled("Param", "p1", 4);
        changes.update("Param", "p1", 3, Map.of("value", "2"));
        assertThat(changes.pending()).extracting(ChangeSet.Change::grant).containsExactly(grant, grant, null);
        assertThat(changes.pending().getFirst().effectiveTime()).isEqualTo(later);
        assertThatThrownBy(() -> changes.in("ds").granted(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void registeredAttributesAreCopies() {
        ChangeSet changes = changeSet();
        Map<String, Object> attributes = new HashMap<>(Map.of("name", "a"));
        changes.update("Gen", "g", 1L, attributes);
        attributes.put("name", "changed");

        assertThat(changes.pending().getFirst().attributes()).containsEntry("name", "a");
        assertThatThrownBy(() -> changes.pending().getFirst().attributes().put("x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void drainHandsOverThePendingChangesAndSavedStatesAccumulate() {
        ChangeSet changes = changeSet();
        changes.delete("Gen", "g-1", 1L);

        assertThat(changes.drain()).hasSize(1);
        assertThat(changes.isEmpty()).isTrue();
        assertThat(changes.pending()).isEmpty();

        changes.recordSaved(List.of(new ChangeSet.Saved("Gen", "g-2", 1L, Map.of("name", "x"))));
        changes.recordSaved(List.of(new ChangeSet.Saved("Gen", "g-3", 1L, null)));
        assertThat(changes.saved()).extracting(ChangeSet.Saved::id).containsExactly("g-2", "g-3");
        assertThat(changes.saved().get(1).attributes()).isEmpty();
    }

    @Test
    void rejectsIncompleteChanges() {
        ChangeSet changes = changeSet();
        assertThatThrownBy(() -> changes.update("Gen", null, 1L, Map.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> changes.insert(" ", Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changes.in("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changes.effectiveAt(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> changes.in("urn:ds").cancelScheduled("Price", "p", 1L))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("effective time");
        assertThatThrownBy(() -> new ChangeSet(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangeSet.Change(null, null, "Gen", null, 0, null, null))
            .isInstanceOf(NullPointerException.class);
    }
}
