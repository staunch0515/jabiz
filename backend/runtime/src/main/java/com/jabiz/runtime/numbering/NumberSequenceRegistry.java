package com.jabiz.runtime.numbering;

import com.jabiz.numbering.NumberSequence;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The number sequences declared as beans. A name declared twice keeps its first declaration here and is reported by
 * the startup check ({@link NumberingChecks}).
 */
@Component
public class NumberSequenceRegistry {

    private final Map<String, NumberSequence> sequences = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();

    public NumberSequenceRegistry(ObjectProvider<NumberSequence> declared) {
        declared.orderedStream().forEach(sequence -> {
            if (sequences.putIfAbsent(sequence.name(), sequence) != null) {
                duplicates.add(sequence.name());
            }
        });
    }

    public Optional<NumberSequence> find(String name) {
        return Optional.ofNullable(name == null ? null : sequences.get(name));
    }

    public Collection<NumberSequence> all() {
        return Collections.unmodifiableCollection(sequences.values());
    }

    List<String> duplicates() {
        return List.copyOf(duplicates);
    }
}
