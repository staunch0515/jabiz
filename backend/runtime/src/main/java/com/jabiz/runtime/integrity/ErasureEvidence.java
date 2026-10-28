package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The signature of a purge's entries (decision D62 item 6): the purge signs what it overwrote with the integrity key,
 * and the verification accepts a changed sealed row as ERASED only through the entries of a purge whose signature
 * holds. The digests in the entries are plain SHA-256 that anyone can compute; without the signature whoever can
 * change the database could account for any change as a purge.
 */
public final class ErasureEvidence {

    /** One {@code sys_erasure_item}. */
    public record Item(String table, String rowKey, List<String> columns, String before, String after) {

        public Item {
            columns = List.copyOf(columns);
        }
    }

    private static final Comparator<Item> ORDER = Comparator.comparing(Item::table).thenComparing(Item::rowKey);

    private ErasureEvidence() {
    }

    /** Hex HMAC-SHA256 over the erasure, its row count and its entries, in an order of their own. */
    public static String sign(IntegrityKey key, UUID erasureId, int rowCount, List<Item> items) {
        return key.sign(text(erasureId, rowCount, items));
    }

    static String text(UUID erasureId, int rowCount, List<Item> items) {
        // Every value is written with its length: no value can pass for another's end or start.
        StringBuilder text = new StringBuilder("jabiz-erasure-purge\n");
        field(text, erasureId.toString());
        field(text, Integer.toString(rowCount));
        items.stream().sorted(ORDER).forEach(item -> {
            field(text, item.table());
            field(text, item.rowKey());
            field(text, Integer.toString(item.columns().size()));
            item.columns().forEach(column -> field(text, column));
            field(text, item.before() == null ? "-" : "+" + item.before());
            field(text, item.after() == null ? "-" : "+" + item.after());
        });
        return text.toString();
    }

    private static void field(StringBuilder text, String value) {
        text.append(value.length()).append(':').append(value).append('\n');
    }
}
