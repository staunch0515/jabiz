package com.jabiz.runtime.storage;

/**
 * A write broke a unique index; {@link #constraintName()} names it when the database reports it, {@link #table()}
 * the table and {@link #detail()} the key ({@code Key (entity_id)=(…) already exists.}) when it reports them.
 */
public class UniqueKeyViolationException extends RuntimeException {

    private final String constraintName;
    private final String table;
    private final String detail;

    public UniqueKeyViolationException(String constraintName, Throwable cause) {
        this(constraintName, null, null, cause);
    }

    public UniqueKeyViolationException(String constraintName, String table, String detail, Throwable cause) {
        super("Unique constraint violated: " + constraintName, cause);
        this.constraintName = constraintName;
        this.table = table;
        this.detail = detail;
    }

    public String constraintName() {
        return constraintName;
    }

    /** The table of the index; null when not reported. */
    public String table() {
        return table;
    }

    /** The database's description of the duplicate key; null when not reported. */
    public String detail() {
        return detail;
    }
}
