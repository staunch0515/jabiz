package com.jabiz.query;

import java.time.Duration;
import java.util.Map;

/**
 * A fully rendered SQL statement (for example from an advanced query template).
 *
 * @param sql        complete SELECT statement with named parameters, without a LIMIT clause
 * @param bindParams named parameters referenced by the statement
 * @param limit      maximum number of rows to return; applied by the storage engine
 */
public record RawQueryPlan(
    String sql,
    Map<String, BoundValue> bindParams,
    int limit,
    Duration timeout
) {}
