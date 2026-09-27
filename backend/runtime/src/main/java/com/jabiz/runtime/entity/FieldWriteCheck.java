package com.jabiz.runtime.entity;

import com.jabiz.entity.EntityDefinition;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Map;

/**
 * A platform check of written values that needs the database, run on every write path (dataset API, entity processes,
 * change sets; ordinary and temporal entities) next to the reference check. Platform-internal; business rules are
 * synchronous and do not use it. The file check ({@code jabiz.file} fields) is one.
 */
public interface FieldWriteCheck {

    /**
     * Checks the values of {@code changedFields} (the fields an insert sets or an update changes); fails with a
     * {@link com.jabiz.entity.ValidationException} listing every problem.
     */
    Mono<Void> verify(EntityDefinition def, Map<String, Object> values, Collection<String> changedFields);
}
