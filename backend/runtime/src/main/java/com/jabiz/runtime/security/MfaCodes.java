package com.jabiz.runtime.security;

import java.util.Arrays;
import java.util.List;

/** Stored form of the recovery code hashes of {@code SecUserMfa}: comma separated hex SHA-256 values. */
public final class MfaCodes {

    private MfaCodes() {}

    public static List<String> hashes(Object stored) {
        if (stored == null || String.valueOf(stored).isBlank()) {
            return List.of();
        }
        return Arrays.stream(String.valueOf(stored).split(",")).filter(hash -> !hash.isBlank()).toList();
    }

    /** Null when no code is left. */
    public static String join(List<String> hashes) {
        return hashes.isEmpty() ? null : String.join(",", hashes);
    }
}
