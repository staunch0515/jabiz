package com.jabiz.runtime.file;

import com.jabiz.file.FilePolicy;
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
 * The file policies declared as beans (docs/design/14-files.md section 3). A name declared twice keeps its first
 * declaration here and is reported by the startup check ({@link FileChecks}), together with every other problem.
 */
@Component
public class FilePolicyRegistry {

    private final Map<String, FilePolicy> policies = new LinkedHashMap<>();
    private final List<String> duplicates = new ArrayList<>();

    public FilePolicyRegistry(ObjectProvider<FilePolicy> declared) {
        declared.orderedStream().forEach(policy -> {
            if (policies.putIfAbsent(policy.name(), policy) != null) {
                duplicates.add(policy.name());
            }
        });
    }

    public Optional<FilePolicy> find(String name) {
        return Optional.ofNullable(name == null ? null : policies.get(name));
    }

    public Collection<FilePolicy> all() {
        return Collections.unmodifiableCollection(policies.values());
    }

    /** Names declared more than once. */
    List<String> duplicates() {
        return List.copyOf(duplicates);
    }
}
