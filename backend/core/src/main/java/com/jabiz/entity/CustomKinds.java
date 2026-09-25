package com.jabiz.entity;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link CustomKindSupport} implementations. Implementations on the class path are found through
 * {@link ServiceLoader} ({@code META-INF/services/com.jabiz.entity.CustomKindSupport}); {@link #register} adds
 * more (mainly for tests). A kind id can be registered only once.
 */
public final class CustomKinds {

    private static final Map<String, CustomKindSupport> SUPPORTS = new ConcurrentHashMap<>();

    static {
        for (CustomKindSupport support : ServiceLoader.load(CustomKindSupport.class, CustomKinds.class.getClassLoader())) {
            register(support);
        }
    }

    private CustomKinds() {}

    /**
     * @throws IllegalStateException if another implementation already uses the kind id
     */
    public static void register(CustomKindSupport support) {
        CustomKindSupport previous = SUPPORTS.putIfAbsent(support.kindId(), support);
        if (previous != null && previous.getClass() != support.getClass()) {
            throw new IllegalStateException("Custom kind " + support.kindId() + " is registered twice: "
                + previous.getClass().getName() + " and " + support.getClass().getName());
        }
    }

    public static Optional<CustomKindSupport> find(String kindId) {
        return Optional.ofNullable(SUPPORTS.get(kindId));
    }

    /**
     * @throws IllegalArgumentException if no implementation is registered for the kind id
     */
    public static CustomKindSupport require(String kindId) {
        return find(kindId).orElseThrow(() -> new IllegalArgumentException(
            "No CustomKindSupport is registered for kind '" + kindId + "'"));
    }
}
