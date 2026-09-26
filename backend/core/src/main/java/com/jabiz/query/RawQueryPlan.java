package com.jabiz.query;

import java.time.Duration;
import java.util.Map;

/**
 * A fully rendered SQL statement (for example from an advanced query template).
 *
 * @param sql        complete SELECT statement with named parameters, run as given: paging is part of the SQL
 *                   (docs/design/05-sql-template.md section 5)
 * @param bindParams named parameters referenced by the statement
 */
public record RawQueryPlan(
    String sql,
    Map<String, BoundValue> bindParams,
    Duration timeout
) {}
