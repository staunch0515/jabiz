package com.jabiz.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Compiled form of an entity query, ready for a storage engine.
 *
 * @param source       FROM item to read from: a table, or for temporal entities the sub-select of their versions
 *                     in effect, aliased; built by {@link QueryCompiler} from validated identifiers only
 * @param whereClause  boolean SQL expression without the WHERE keyword; empty if unfiltered
 * @param bindParams   named parameters referenced by whereClause
 */
public record PhysicalQueryPlan(
    String source,
    String whereClause,
    Map<String, BoundValue> bindParams,
    List<PhysicalSort> sorts,
    int offset,
    int limit,
    Duration timeout
) {
    public record PhysicalSort(String physicalColumn, boolean ascending) {}
}
