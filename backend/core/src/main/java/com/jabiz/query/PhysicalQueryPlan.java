package com.jabiz.query;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Compiled form of an entity query, ready for a storage engine.
 *
 * @param targetTable  physical table to read from
 * @param whereClause  boolean SQL expression without the WHERE keyword; empty if unfiltered
 * @param bindParams   named parameters referenced by whereClause
 */
public record PhysicalQueryPlan(
    String targetTable,
    String whereClause,
    Map<String, BoundValue> bindParams,
    List<PhysicalSort> sorts,
    int offset,
    int limit,
    Duration timeout
) {
    public record PhysicalSort(String physicalColumn, boolean ascending) {}
}
