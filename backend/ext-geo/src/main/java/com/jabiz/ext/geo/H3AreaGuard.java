package com.jabiz.ext.geo;

import com.jabiz.entity.TransitionGuard;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transition guard that requires the entity to be located in one of a set of H3 cells when it enters the
 * target state (docs/design/02-metamodel.md section 4). The location is taken from the change if it supplies
 * one, else from the current values.
 */
public final class H3AreaGuard implements TransitionGuard {

    /** The location field has no value. */
    public static final String LOCATION_MISSING = "GEO_LOCATION_MISSING";
    /** The location is outside the allowed cells. */
    public static final String LOCATION_REJECTED = "GEO_LOCATION_REJECTED";

    private final String locationField;
    private final Set<Long> cells;

    private H3AreaGuard(String locationField, Set<Long> cells) {
        this.locationField = locationField;
        this.cells = Set.copyOf(cells);
    }

    /** Guard accepting only the given cells in {@code locationField} (an H3 field). */
    public static H3AreaGuard within(String locationField, Set<Long> cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("an area needs at least one cell");
        }
        return new H3AreaGuard(locationField, cells);
    }

    @Override
    public List<Violation> check(String from, String to, Map<String, Object> current, Map<String, Object> incoming,
        ValidationContext ctx) {
        Object cell = incoming.containsKey(locationField) ? incoming.get(locationField) : current.get(locationField);
        if (cell == null) {
            return List.of(new Violation(locationField, LOCATION_MISSING,
                "Location [" + locationField + "] is required for status [" + to + "]", Map.of("status", to)));
        }
        long index = ((Number) cell).longValue();
        if (!cells.contains(index)) {
            return List.of(new Violation(locationField, LOCATION_REJECTED,
                String.format("Cell [0x%x] does not allow status [%s]", index, to), Map.of("status", to)));
        }
        return List.of();
    }
}
