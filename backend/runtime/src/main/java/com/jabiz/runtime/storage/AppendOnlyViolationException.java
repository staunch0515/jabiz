package com.jabiz.runtime.storage;

/**
 * The database refused to update, delete or truncate an append-only table (decision D5, SQLSTATE
 * {@value #SQL_STATE}). The platform never issues such statements, so this signals a defect and is reported as a
 * severe error.
 */
public class AppendOnlyViolationException extends RuntimeException {

    public static final String SQL_STATE = "JZ001";

    public AppendOnlyViolationException(String message, Throwable cause) {
        super(message, cause);
    }
}
