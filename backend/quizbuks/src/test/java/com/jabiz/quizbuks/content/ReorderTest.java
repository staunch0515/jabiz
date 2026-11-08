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

    private static Map<String, Integer> places(Object... pairs) {
        Map<String, Integer> places = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            places.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return places;
    }

    /** The items in their order after applying {@code moves}. */
    private static List<String> after(Map<String, Integer> current, Map<String, Integer> moves) {
        Map<String, Integer> all = new LinkedHashMap<>(current);
        all.putAll(moves);
        return all.entrySet().stream().sorted(Map.Entry.comparingByValue()).map(Map.Entry::getKey).toList();
    }

    @Test
    void aMovedItemTakesAPlaceBetweenItsNewNeighboursWhileThereIsAGap() {
        Map<String, Integer> current = places("a", 10, "b", 20, "c", 30, "d", 40);
        assertThat(Reorder.move(current, "d", "b", 9_999)).containsExactly(Map.entry("d", 15));
        assertThat(Reorder.move(current, "a", null, 9_999)).containsExactly(Map.entry("a", 5_020));
        assertThat(Reorder.move(current, "c", "a", 9_999)).containsExactly(Map.entry("c", 5));
        // Where it is already: nothing.
        assertThat(Reorder.move(current, "b", "c", 9_999)).isEmpty();
        assertThat(Reorder.move(current, "d", null, 9_999)).isEmpty();
        assertThat(Reorder.move(current, "b", "b", 9_999)).isEmpty();
    }

    @Test
    void withoutAGapEveryItemIsSpreadAgainOnce() {
        Map<String, Integer> current = places("a", 1, "b", 2, "c", 3);
        Map<String, Integer> moves = Reorder.move(current, "c", "b", 9);
        // Three items over 1..9: steps of 2.
        assertThat(moves).containsExactly(Map.entry("c", 4), Map.entry("a", 2), Map.entry("b", 6));
        assertThat(after(current, moves)).containsExactly("a", "c", "b");
    }

    @Test
    void unknownItemsAreRefused() {
        Map<String, Integer> current = places("a", 1, "b", 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Reorder.move(current, "x", null, 9))
            .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Reorder.move(current, "a", "x", 9))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anyMoveGivesTheRequestedOrderWithinTheColumn() {
        java.util.Random random = new java.util.Random(7);
        for (int round = 0; round < 200; round++) {
            int n = 2 + random.nextInt(60);
            int max = n + random.nextInt(200);
            Map<String, Integer> current = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                current.put("q" + i, i + 1);
            }
            for (int step = 0; step < 50; step++) {
                List<String> order = after(current, Map.of());
                String item = order.get(random.nextInt(n));
                String before = random.nextInt(5) == 0 ? null : order.get(random.nextInt(n));
                List<String> expected = new java.util.ArrayList<>(order);
                expected.remove(item);
                expected.add(before == null || before.equals(item) ? (before == null ? expected.size()
                    : order.indexOf(item)) : expected.indexOf(before), item);
                Map<String, Integer> ordered = new LinkedHashMap<>();
                order.forEach(key -> ordered.put(key, current.get(key)));
                Map<String, Integer> moves = Reorder.move(ordered, item, before, max);
                current.putAll(moves);
                assertThat(after(current, Map.of())).containsExactlyElementsOf(expected);
                assertThat(current.values()).allSatisfy(seq -> assertThat(seq).isBetween(1, max));
                assertThat(new java.util.HashSet<>(current.values())).hasSize(n);
            }
        }
    }
}
