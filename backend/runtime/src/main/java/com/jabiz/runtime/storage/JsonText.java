package com.jabiz.runtime.storage;

import java.util.Objects;

/** Serialized JSON to be stored in a JSON column. */
public record JsonText(String json) {
    public JsonText {
        Objects.requireNonNull(json, "json must not be null");
    }
}
