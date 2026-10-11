package com.jabiz.inbound;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The headers of an inbound webhook request as a signature scheme reads them: every value of a header, looked up
 * regardless of case (HTTP header names are case-insensitive).
 */
@FunctionalInterface
public interface InboundHeaders {

    /** Every value of the header, in the order received; empty when it is absent. */
    List<String> values(String name);

    /** The headers of a map, names compared regardless of case. */
    static InboundHeaders of(Map<String, List<String>> headers) {
        TreeMap<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (headers != null) {
            headers.forEach((name, values) -> copy.merge(name, values == null ? List.of() : List.copyOf(values),
                (a, b) -> {
                    List<String> both = new ArrayList<>(a);
                    both.addAll(b);
                    return List.copyOf(both);
                }));
        }
        return name -> name == null ? List.of() : copy.getOrDefault(name, List.of());
    }
}
