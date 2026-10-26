package com.jabiz.quizbuks.publishing;

import com.jabiz.entity.EntityBuilder;

import java.util.List;

/**
 * Who published a publication (docs/quizbuks/shopify/S0-publisher.md, D-S0-1): a pair of fields on every record that
 * is a copy of a publication's owner. {@code publisherKind} says which merchant system the owner belongs to and
 * {@code publisherId} is the owner's key in that system: for {@link #SPONSOR} a sponsor's user id, as the records
 * carried before S0; for {@link #SHOPIFY_SHOP} the shop record's key (S1). A merchant's own records (top-ups,
 * receipts, mail server, AI jobs) keep their {@code sponsorId}: they belong to the sponsors' system alone.
 */
public final class PublisherKind {

    /** A sponsor of the merchants' console: a platform user who onboarded ({@code QbSponsor}). */
    public static final String SPONSOR = "SPONSOR";
    /** A Shopify shop that installed the app (the Shopify module, S1); none exist before it. */
    public static final String SHOPIFY_SHOP = "SHOPIFY_SHOP";

    public static final List<String> ALL = List.of(SPONSOR, SHOPIFY_SHOP);

    public static final String DICTIONARY = "urn:jabiz:dict:quizbuks:publisher-kind";

    public static final String KIND_FIELD = "publisherKind";
    public static final String ID_FIELD = "publisherId";

    /** The id's column on an ordinary table, renamed from {@code sponsor_id} by V29. */
    public static final String ID_COLUMN = "publisher_id";
    /**
     * The id's column on an append-only (temporal) table: still {@code sponsor_id}. The platform's integrity seal
     * records each table's columns and digests them again when verifying, so renaming a sealed column would report
     * every row sealed before as modified (docs/design/21-audit-retention.md section 2.1); V30 renamed it back.
     */
    public static final String SEALED_ID_COLUMN = "sponsor_id";

    /**
     * Declares the pair on an ordinary table: both immutable and required, the id a text of 128 like the user ids it
     * held, in {@link #ID_COLUMN}.
     *
     * @param processOnly whether the generic API may not write them (the record is written by the processes only)
     */
    public static void declare(EntityBuilder eb, boolean processOnly) {
        declare(eb, processOnly, ID_COLUMN);
    }

    /**
     * Declares the pair on an append-only (temporal) table, whose id stays in {@link #SEALED_ID_COLUMN}: its field is
     * {@code publisherId} all the same.
     */
    public static void declareSealed(EntityBuilder eb, boolean processOnly) {
        declare(eb, processOnly, SEALED_ID_COLUMN);
    }

    private static void declare(EntityBuilder eb, boolean processOnly, String idColumn) {
        eb.field(KIND_FIELD, f -> {
            f.physicalColumn("publisher_kind").immutable(true).required(true);
            if (processOnly) {
                f.processOnly();
            }
            f.asCode(DICTIONARY, ALL.toArray(String[]::new));
        });
        eb.field(ID_FIELD, f -> {
            f.physicalColumn(idColumn).immutable(true).required(true);
            if (processOnly) {
                f.processOnly();
            }
            f.asText(128);
        });
    }

    /** Whether the kind is a sponsor's (null, as a record from before S0 could read, counts as one). */
    public static boolean sponsor(Object kind) {
        return kind == null || SPONSOR.equals(kind);
    }

    private PublisherKind() {}
}
