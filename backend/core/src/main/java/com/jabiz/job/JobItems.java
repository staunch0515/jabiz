package com.jabiz.job;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * How a per-item job selects its items (docs/design/11-ledger-events-jobs.md section 4.1; decision D42): an SQL
 * template, run as the system in a short read-only transaction, in the template's {@code list.defaultSort} and
 * {@code list.key} order. Each result row is one item, named by the value of its key column. The template is a
 * declaration: it is checked at startup and leaves no operation record; business parameters the item needs are read by
 * the item's process ({@code LoadParams}).
 *
 * <p>The selection only proposes items: the state may change before an item runs, so the item's process takes its
 * lock and reads again before it acts, and does nothing when the item is no longer due.
 *
 * @param templateId the SQL template; it declares {@code list.defaultSort} and {@code list.key}
 * @param parameters the template's parameters from the scheduled time of the run; synchronous and free of I/O
 * @param keyColumn  the result column naming each item: 1 to 36 characters of {@link #ITEM_KEY} (a UUID fits)
 */
public record JobItems(String templateId, Function<Instant, Map<String, Object>> parameters, String keyColumn) {

    /** What an item key may be: it goes into the idempotency key of the item's process. */
    public static final Pattern ITEM_KEY = Pattern.compile("[A-Za-z0-9._:-]{1,36}");

    public JobItems {
        if (templateId == null || templateId.isBlank()) {
            throw new IllegalArgumentException("templateId must not be blank");
        }
        Objects.requireNonNull(parameters, "parameters must not be null");
        if (keyColumn == null || keyColumn.isBlank()) {
            throw new IllegalArgumentException("keyColumn must not be blank");
        }
    }

    /** Items are the rows of the template {@code templateId}, each named by its column {@code keyColumn}. */
    public static JobItems template(String templateId, Function<Instant, Map<String, Object>> parameters,
        String keyColumn) {
        return new JobItems(templateId, parameters, keyColumn);
    }

    /** As {@link #template(String, Function, String)}, for a template without parameters. */
    public static JobItems template(String templateId, String keyColumn) {
        return new JobItems(templateId, scheduledTime -> Map.of(), keyColumn);
    }
}
