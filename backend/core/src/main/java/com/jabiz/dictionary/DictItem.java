package com.jabiz.dictionary;

import java.util.Objects;

/**
 * One entry of a dictionary, labelled in one language.
 *
 * @param enabled disabled codes stay readable (stored data may use them) but are rejected as new input
 */
public record DictItem(String code, String label, int sortOrder, boolean enabled) {
    public DictItem {
        Objects.requireNonNull(code, "code must not be null");
        label = label == null ? code : label;
    }
}
