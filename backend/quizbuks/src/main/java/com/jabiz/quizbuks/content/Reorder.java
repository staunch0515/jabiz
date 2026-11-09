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

    /**
     * F-S6: the places that change when one item moves before another ({@code before}; null moves it to the end),
     * without writing every item. It takes a place between its new neighbours while there is a gap; only when there is
     * none are all the items spread again over 1..{@code max} with equal steps, the one write of every item.
     *
     * @param current every item's place, in their order
     * @return the new place of each item that moves (the moved item first); empty when it stays where it is
     * @throws IllegalArgumentException when {@code item} or {@code before} is not one of the items
     */
    public static <T> Map<T, Integer> move(Map<T, Integer> current, T item, T before, int max) {
        if (!current.containsKey(item) || (before != null && !current.containsKey(before))) {
            throw new IllegalArgumentException("Not an item: " + (current.containsKey(item) ? before : item));
        }
        List<T> order = new java.util.ArrayList<>(current.keySet());
        int from = order.indexOf(item);
        if (item.equals(before) || (before == null ? from == order.size() - 1
            : from + 1 < order.size() && order.get(from + 1).equals(before))) {
            return Map.of();
        }
        order.remove(item);
        int at = before == null ? order.size() : order.indexOf(before);
        int previous = at == 0 ? 0 : current.get(order.get(at - 1));
        int next = before == null ? max + 1 : current.get(before);
        Map<T, Integer> moves = new LinkedHashMap<>();
        if (next - previous > 1) {
            moves.put(item, previous + (next - previous) / 2);
            return moves;
        }
        order.add(at, item);
        int step = Math.max(1, max / (order.size() + 1));
        if ((long) step * order.size() > max) {
            throw new IllegalStateException("No place left: " + order.size() + " items, at most " + max);
        }
        moves.put(item, step * (at + 1));
        for (int i = 0; i < order.size(); i++) {
            T other = order.get(i);
            if (!other.equals(item) && current.get(other) != step * (i + 1)) {
                moves.put(other, step * (i + 1));
            }
        }
        return moves;
    }

    private Reorder() {}
}
