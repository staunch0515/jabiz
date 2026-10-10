package com.jabiz.quizbuks.content;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A new order of a quiz's questions or materials (docs/quizbuks/plans/Q3-content.md, D-Q3-4). The request lists
 * every item exactly once, or it was made from a page that no longer shows the quiz as it is: then it is refused
 * rather than guessed at. The items get the positions 1..n; only those whose position changes are written.
 */
public final class Reorder {

    /**
     * @param current   each item's present position ({@code seq})
     * @param requested every item, in the new order
     * @return the new position of each item that moves, in the requested order; empty when the request does not list
     *         exactly the current items (one missing, one unknown or one twice)
     */
    public static <T> Optional<Map<T, Integer>> changes(Map<T, Integer> current, List<T> requested) {
        Set<T> seen = new HashSet<>();
        for (T item : requested) {
            if (item == null || !current.containsKey(item) || !seen.add(item)) {
                return Optional.empty();
            }
        }
        if (seen.size() != current.size()) {
            return Optional.empty();
        }
        Map<T, Integer> moves = new LinkedHashMap<>();
        for (int i = 0; i < requested.size(); i++) {
            T item = requested.get(i);
            if (current.get(item) == null || current.get(item) != i + 1) {
                moves.put(item, i + 1);
            }
        }
        return Optional.of(moves);
    }

    /**
     * The place of a new item at the end.
     *
     * @param seq   the new item's place
     * @param moves the places of existing items that move first, in their order
     */
    public record Next<T>(int seq, Map<T, Integer> moves) {
        public Next {
            moves = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(moves));
        }
    }

    /**
     * The place after the last one, while it fits ({@code max}, the largest place the column holds). Deletions leave
     * gaps, so the last place can grow past the number of items; when it would pass {@code max}, the items are first
     * numbered 1..n again (in their order) and the new one goes to n + 1.
     *
     * @param current each item's place, in their order
     */
    public static <T> Next<T> next(Map<T, Integer> current, int max) {
        int last = current.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        if (last < max) {
            return new Next<>(last + 1, Map.of());
        }
        Map<T, Integer> moves = new LinkedHashMap<>();
        int place = 0;
        for (Map.Entry<T, Integer> item : current.entrySet()) {
            place++;
            if (item.getValue() != place) {
                moves.put(item.getKey(), place);
            }
        }
        if (place >= max) {
            throw new IllegalStateException("No place left: " + place + " items, at most " + max);
        }
        return new Next<>(place + 1, moves);
    }

    private Reorder() {}
}
