package com.jabiz.runtime;

public enum EntityAction {
    INSERT, UPDATE, DELETE,
    /**
     * Cancels the version of a temporal entity scheduled for the change's effective time
     * (docs/design/04-temporal-append-only.md section 4.1); the instance's version is that version's number.
     */
    CANCEL_SCHEDULED;

    /** Parses common action names case-insensitively. */
    public static EntityAction from(String text) {
        return switch (text.trim().toUpperCase()) {
            case "INSERT", "CREATE", "ADD" -> INSERT;
            case "UPDATE", "MODIFY", "SAVE" -> UPDATE;
            case "DELETE", "REMOVE" -> DELETE;
            case "CANCEL_SCHEDULED", "CANCEL" -> CANCEL_SCHEDULED;
            default -> throw new IllegalArgumentException("Unsupported action: " + text);
        };
    }
}
