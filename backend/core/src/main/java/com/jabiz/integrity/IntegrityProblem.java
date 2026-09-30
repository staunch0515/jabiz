package com.jabiz.integrity;

import java.util.Objects;

/**
 * Something a verification found (docs/design/21-audit-retention.md section 2.3).
 *
 * @param kind   what is wrong
 * @param sealNo the block concerned, or null
 * @param table  the table concerned, or null
 * @param key    the row concerned (primary key as a JSON array), or null
 * @param detail a sentence for people
 */
public record IntegrityProblem(Kind kind, Long sealNo, String table, String key, String detail) {

    public enum Kind {
        /** The row's content no longer has the digest it was sealed with. */
        MODIFIED,
        /** A sealed row is gone. */
        MISSING,
        /** A block's hash, link or number is not what the chain says: blocks were changed, removed or inserted. */
        CHAIN_BROKEN,
        /** A block's rows no longer add up to its Merkle root: seal entries were changed, added or removed. */
        SEAL_ALTERED,
        /** A block was signed with another key than the current one, so it cannot be checked. */
        OTHER_KEY,
        /** A table with sealed rows no longer refuses updates and deletions: its append-only guard is off or gone. */
        UNPROTECTED
    }

    public IntegrityProblem {
        Objects.requireNonNull(kind, "kind must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
