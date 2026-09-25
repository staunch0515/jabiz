package com.jabiz.runtime.storage;

/** A write broke a unique index; {@link #constraintName()} names it when the database reports it. */
public class UniqueKeyViolationException extends RuntimeException {

    private final String constraintName;

    public UniqueKeyViolationException(String constraintName, Throwable cause) {
        super("Unique constraint violated: " + constraintName, cause);
        this.constraintName = constraintName;
    }

    public String constraintName() {
        return constraintName;
    }
}
