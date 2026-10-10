package com.jabiz.quizbuks.content;

import java.util.ArrayList;
import java.util.List;

/** Splits a list into consecutive chunks of at most a given size, in order. */
final class Chunks {

    static <T> List<List<T>> of(List<T> items, int size) {
        if (size < 1) {
            throw new IllegalArgumentException("A chunk holds at least one item, not " + size);
        }
        List<List<T>> chunks = new ArrayList<>();
        for (int from = 0; from < items.size(); from += size) {
            chunks.add(List.copyOf(items.subList(from, Math.min(items.size(), from + size))));
        }
        return List.copyOf(chunks);
    }

    private Chunks() {}
}
