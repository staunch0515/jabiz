package com.jabiz.runtime.external;

import com.jabiz.external.ExternalJobType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The declared external job types (decision D38 item 3), by name. Nothing is refused here: {@link ExternalJobChecks}
 * reports every problem at startup; a name declared twice is found as its first declaration.
 */
@Component
public class ExternalJobRegistry {

    private final List<ExternalJobType<?, ?>> declared;
    private final Map<String, ExternalJobType<?, ?>> byName;

    @Autowired
    public ExternalJobRegistry(ObjectProvider<ExternalJobType<?, ?>> types) {
        this(types.orderedStream().toList());
    }

    public ExternalJobRegistry(List<ExternalJobType<?, ?>> types) {
        this.declared = List.copyOf(types);
        Map<String, ExternalJobType<?, ?>> named = new LinkedHashMap<>();
        for (ExternalJobType<?, ?> type : types) {
            if (type.name() != null) {
                named.putIfAbsent(type.name(), type);
            }
        }
        this.byName = Collections.unmodifiableMap(named);
    }

    /** Every declaration, duplicates included, in bean order. */
    public List<ExternalJobType<?, ?>> declared() {
        return declared;
    }

    /** The type of that name. */
    public Optional<ExternalJobType<?, ?>> find(String name) {
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }

    /** The declared names, in bean order. */
    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    @Override
    public String toString() {
        return "ExternalJobRegistry" + byName.keySet();
    }
}
