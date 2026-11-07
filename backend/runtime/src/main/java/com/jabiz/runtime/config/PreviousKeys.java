package com.jabiz.runtime.config;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The retired keys of a key in rotation (decision D78): {@code *_PREVIOUS}, Base64 keys separated by commas. Empty
 * when none is set; a malformed or short one stops the application, as the current key does.
 */
public final class PreviousKeys {

    private PreviousKeys() {}

    /**
     * @param configured the property's value (may be null or blank)
     * @param property   its name, for the message
     * @param minBytes   the shortest key accepted
     */
    public static List<byte[]> parse(String configured, String property, int minBytes) {
        List<byte[]> keys = new ArrayList<>();
        if (configured == null || configured.isBlank()) {
            return keys;
        }
        int position = 0;
        for (String part : configured.split(",")) {
            position++;
            String text = part.strip();
            if (text.isEmpty()) {
                continue;
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(text);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(property + ": key " + position + " is not valid Base64");
            }
            if (key.length < minBytes) {
                throw new IllegalStateException(property + ": key " + position + " must decode to at least " + minBytes
                    + " bytes");
            }
            keys.add(key);
        }
        return keys;
    }
}
