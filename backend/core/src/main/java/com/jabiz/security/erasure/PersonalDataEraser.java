package com.jabiz.security.erasure;

import java.util.List;

/**
 * What an application erases of a user when the user's account is deleted (docs/design/10-security.md section 17;
 * decision D62 item 7): its entities that point to the user, and which of their fields are personal. Declarative,
 * synchronous and free of I/O like every business extension point; the platform does the writing.
 *
 * <p>When the account is erased ({@code ACCOUNT_ERASE}) the platform removes or clears the declared rows through the
 * ordinary write path, in the order declared; when the erasure is purged ({@code ERASURE_PURGE}) it overwrites the
 * declared fields in every version and append-only row, and in the audit records of those rows. The application writes
 * no erasure SQL of its own and never turns off the append-only guard.
 */
public interface PersonalDataEraser {

    /** The application's entities that hold personal data of a user, in the order they are erased. */
    List<ErasedEntity> entities();

    /** The data {@link #notices} reads: rows of datasets whose field holds the user id. None by default. */
    default List<ErasureLoad> loads() {
        return List.of();
    }

    /**
     * What the user should know before asking for the deletion (for example: a balance that would be lost), shown
     * with the request; none by default. The request goes ahead all the same: these inform, they do not refuse.
     */
    default List<ErasureNotice> notices(ErasureSubject subject) {
        return List.of();
    }
}
