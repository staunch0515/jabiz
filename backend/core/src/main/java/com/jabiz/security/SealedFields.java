package com.jabiz.security;

import java.util.List;

/**
 * The fields where an application keeps values it sealed with {@code SealSecret} (decision D78), so that a new
 * {@code JABIZ_SEAL_KEY} can take over: the platform stores each value again under the current key
 * ({@code jabiz.key-rotation}) while the retired key is still configured. Declarative, synchronous and free of I/O.
 * A sealed field no bean declares keeps the retired key's value, which no longer opens once that key is removed.
 */
public interface SealedFields {

    List<SealedField> fields();
}
