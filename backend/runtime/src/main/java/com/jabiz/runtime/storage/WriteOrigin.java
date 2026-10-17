package com.jabiz.runtime.storage;

import java.util.Objects;

/**
 * Where the writes made within a Reactor context were registered, such as {@code ORDER_SHIP v1 (seq 42) step 'Post
 * the sale'}: a write held back and written later, with another statement (ROADMAP phase 16p-2, T9), is named by it in
 * the log when it fails there (P-7). The error itself is the write's own, whichever statement carried it.
 */
public record WriteOrigin(String description) {
    public WriteOrigin {
        Objects.requireNonNull(description, "description must not be null");
    }
}
