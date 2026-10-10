package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ReorderTest {

    private static final Map<String, Integer> CURRENT = new LinkedHashMap<>(Map.of("a", 1, "b", 2, "c", 3));

    @Test
    void movesOnlyWhatChangesPlace() {
        assertThat(Reorder.changes(CURRENT, List.of("a", "c", "b"))).hasValue(Map.of("c", 2, "b", 3));
        assertThat(Reorder.changes(CURRENT, List.of("a", "b", "c"))).hasValue(Map.of());
    }

    @Test
    void closesGapsLeftByDeletions() {
        // Deleting leaves gaps; a reorder numbers the items 1..n again.
        Map<String, Integer> gaps = Map.of("a", 1, "c", 3, "d", 7);
        assertThat(Reorder.changes(gaps, List.of("a", "c", "d"))).hasValue(Map.of("c", 2, "d", 3));
    }

    @Test
    void refusesAnythingButTheCurrentItemsOnceEach() {
        assertThat(Reorder.changes(CURRENT, List.of("a", "b"))).isEmpty();
        assertThat(Reorder.changes(CURRENT, List.of("a", "b", "c", "d"))).isEmpty();
        assertThat(Reorder.changes(CURRENT, List.of("a", "b", "b"))).isEmpty();
        assertThat(Reorder.changes(CURRENT, List.of("a", "b", "x"))).isEmpty();
        assertThat(Reorder.changes(Map.of(), List.of())).hasValue(Map.of());
    }

    @Test
    void aNewItemGoesAfterTheLastWhileThereIsRoom() {
        Map<String, Integer> places = new LinkedHashMap<>();
        places.put("a", 1);
        places.put("b", 9_998);
        assertThat(Reorder.next(places, 9_999)).isEqualTo(new Reorder.Next<>(9_999, Map.of()));
        assertThat(Reorder.next(Map.<String, Integer>of(), 9_999)).isEqualTo(new Reorder.Next<>(1, Map.of()));
    }

    @Test
    void atTheLastPlaceTheItemsAreNumberedAgainFirst() {
        Map<String, Integer> places = new LinkedHashMap<>();
        places.put("a", 1);
        places.put("b", 5_000);
        places.put("c", 9_999);
        Reorder.Next<String> next = Reorder.next(places, 9_999);
        assertThat(next.seq()).isEqualTo(4);
        assertThat(next.moves()).containsExactly(Map.entry("b", 2), Map.entry("c", 3));
    }
}
