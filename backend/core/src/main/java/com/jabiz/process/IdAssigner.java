package com.jabiz.process;

import java.util.Map;

/** Determines the primary key of an instance registered for insertion (see {@link ChangeSet.Target#insert}). */
@FunctionalInterface
public interface IdAssigner {

    /** Assigner for contexts created outside the platform (tests): no key is known or generated. */
    IdAssigner NONE = (entityType, attributes) -> null;

    /**
     * @param attributes values of the new instance; the assigner adds a generated key to them
     * @return the key given in {@code attributes}, else a generated one when the entity's key is generated, else null
     */
    Object assign(String entityType, Map<String, Object> attributes);
}
