package com.jabiz.runtime;

public enum EntityAction {
    INSERT, UPDATE, DELETE;

    /** Parses common action names case-insensitively. */
    public static EntityAction from(String text) {
        return switch (text.trim().toUpperCase()) {
            case "INSERT", "CREATE", "ADD" -> INSERT;
            case "UPDATE", "MODIFY", "SAVE" -> UPDATE;
            case "DELETE", "REMOVE" -> DELETE;
            default -> throw new IllegalArgumentException("Unsupported action: " + text);
        };
    }
}
