package com.jabiz.dataset;

import java.util.Objects;

/**
 * How a dataset's rows belong to an entry scope through another entity (decision D64): a row is in the session's
 * scope when its field's value is a {@code keyField} of a row of {@code entity} whose {@code scopeField} is the
 * session's value. QuizBuks: a sponsor's rows ({@code userId}) through its assignment to an operator
 * ({@code QbSponsorOperator.userId}, {@code operatorId}). The entity is an ordinary table (checked at startup).
 *
 * @param entity     the mapping entity
 * @param keyField   its field holding the values of the dataset's field
 * @param scopeField its field holding the scope's values
 */
public record EntryScopeVia(String entity, String keyField, String scopeField) {

    public EntryScopeVia {
        Objects.requireNonNull(entity, "entity must not be null");
        Objects.requireNonNull(keyField, "keyField must not be null");
        Objects.requireNonNull(scopeField, "scopeField must not be null");
    }

    public static EntryScopeVia of(String entity, String keyField, String scopeField) {
        return new EntryScopeVia(entity, keyField, scopeField);
    }
}
