package com.jabiz.security;

import java.util.List;

/**
 * The fields where an application keeps values it sealed with {@code SealSecret} (decision D78), so that a new
 * {@code JABIZ_SEAL_KEY} can take over: the platform stores each value again under the current key
 * ({@code jabiz.key-rotation}) while the retired key is still configured. Declarative, synchronous and free of I/O.
 * A sealed field no bean declares keeps the retired key's value, which no longer opens once that key is removed.
 *
 * <p>Any number of beans may declare fields (the platform has one of its own; an application may have one per
 * package): the platform takes them all together. Each {@code entity.field} is declared once over all of them; a field
 * declared twice is a startup error ({@code KEYS}).
 */
public interface SealedFields {

    List<SealedField> fields();
}
