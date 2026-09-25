package com.jabiz.runtime.process.entity;

import com.jabiz.entity.EntityDefinition;

/**
 * Issues primary key values for entities whose identity field is declared as generated.
 * Replace the default bean to use another scheme, for example time-ordered identifiers.
 */
@FunctionalInterface
public interface EntityIdGenerator {

    Object next(EntityDefinition definition);
}
