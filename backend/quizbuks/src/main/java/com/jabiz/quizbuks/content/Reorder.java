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

    private Reorder() {}
}
