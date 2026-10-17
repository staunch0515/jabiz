package com.jabiz.runtime.storage;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What a transaction knows of the instances it wrote and read (decision D46 rules 3 and 4). */
class TransactionStateTest {

    private static TransactionState.Read read(String dataset) {
        return new TransactionState.Read(dataset, Map.of("tenant", "a"), "instance", 3, true);
    }

    @Test
    void insertionsAndReadsAreKnownUntilTheInstanceIsWritten() {
        TransactionState state = new TransactionState();
        state.inserted("Order", "o1", Map.of("status", "PLACED"));
        state.read("Account", "a1", read("accounts"));

        assertThat(state.find("Order", "o1")).containsInstanceOf(TransactionState.Inserted.class);
        assertThat(state.find("Account", "a1")).contains(read("accounts"));
        assertThat(state.find("Account", "a2")).isEmpty();
        assertThat(state.find("Order", null)).isEmpty();
        // The same key of another entity is another instance.
        assertThat(state.find("Account", "o1")).isEmpty();

        state.written("Order", "o1");
        assertThat(state.find("Order", "o1")).isEmpty();
    }

    @Test
    void theLatestReadAnswers() {
        TransactionState state = new TransactionState();
        state.inserted("Account", "a1", Map.of());
        state.read("Account", "a1", read("accounts"));
        state.read("Account", "a1", read("other"));

        assertThat(state.find("Account", "a1")).contains(read("other"));
    }

    @Test
    void whatIsKnownIsCopiedNotShared() {
        Map<String, Object> values = new HashMap<>(Map.of("status", "PLACED"));
        TransactionState state = new TransactionState();
        state.inserted("Order", "o1", values);
        values.put("status", "CHANGED");

        assertThat(((TransactionState.Inserted) state.find("Order", "o1").orElseThrow()).state())
            .containsEntry("status", "PLACED");
    }

    @Test
    void aRollbackToASavepointRestoresWhatWasKnownButNotTheCount() {
        TransactionState state = new TransactionState();
        state.read("Account", "a1", read("accounts"));
        state.countStatement();
        TransactionState saved = state.snapshot();

        state.written("Account", "a1");
        state.inserted("Order", "o1", Map.of());
        state.countStatement();
        state.restore(saved);

        assertThat(state.find("Account", "a1")).contains(read("accounts"));
        assertThat(state.find("Order", "o1")).isEmpty();
        assertThat(state.statements()).isEqualTo(2);
    }

    @Test
    void numbersDrawnAheadAreTakenInOrderAndARollbackDoesNotGiveThemBack() {
        TransactionState state = new TransactionState();
        state.reserveSequence(List.of(11L, 12L));
        TransactionState saved = state.snapshot();

        assertThat(state.takeReservedSequence(10L)).contains(11L);
        state.restore(saved);

        // A number once taken may have been written and rolled back: it is a gap, never used twice.
        assertThat(state.takeReservedSequence(10L)).contains(12L);
        assertThat(state.takeReservedSequence(10L)).isEmpty();
    }

    @Test
    void aSubProcessNeverTakesANumberBelowItsParentsAndANewRootReplacesWhatWasLeft() {
        TransactionState state = new TransactionState();
        state.reserveSequence(List.of(11L, 12L, 13L));
        // A later root of the same transaction (number 20) draws its own: the earlier ones are gone.
        state.reserveSequence(List.of(21L));
        assertThat(state.takeReservedSequence(20L)).contains(21L);

        state.reserveSequence(List.of(31L, 32L));
        assertThat(state.takeReservedSequence(31L)).contains(32L);
        assertThat(state.takeReservedSequence(31L)).isEmpty();
    }

    @Test
    void aTransactionKeepsABoundedNumberOfReads() {
        TransactionState state = new TransactionState();
        for (int i = 0; i < TransactionState.MAX_KNOWN + 5; i++) {
            state.read("Account", "a" + i, read("accounts"));
        }
        assertThat(state.find("Account", "a0")).isPresent();
        assertThat(state.find("Account", "a" + (TransactionState.MAX_KNOWN + 1))).isEmpty();
        // A known one is still refreshed.
        state.read("Account", "a0", read("other"));
        assertThat(state.find("Account", "a0")).contains(read("other"));
    }
}
