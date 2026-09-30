package com.jabiz.runtime.retention;

/** Permission codes of retention, legal holds and the open-format export (docs/design/21-audit-retention.md). */
public final class RetentionPermissions {

    /** Read the retention policies and what is past its retention. */
    public static final String READ = "retention.read";
    /** Read the legal holds. */
    public static final String HOLD_READ = "legal.hold.read";
    /** Place and release legal holds. */
    public static final String HOLD_WRITE = "legal.hold.write";
    /** Export data in open formats; each dataset also needs its own read permission. */
    public static final String EXPORT = "data.export";

    private RetentionPermissions() {}
}
