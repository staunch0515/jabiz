package com.jabiz.temporal;

/**
 * Why a version was written ({@code op_process_item.action}, docs/design/04-temporal-append-only.md section 2.2).
 */
public enum VersionAction {
    INSERT,
    UPDATE,
    /** Tombstone. */
    DELETE,
    /** Copy of a later version carried over a write with an earlier effective time (decision D1). */
    REBASE,
    /** Restoration written by the revert of an operation (decision D2). */
    REVERT,
    /** Cancellation of a scheduled version (section 4.1). */
    CANCEL
}
