package com.jabiz.runtime.approval;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** The {@link ControlTarget} beans by the entities they change; each entity has one. */
@Component
public class ControlTargets {

    private final Map<String, ControlTarget> byEntity;

    public ControlTargets(ObjectProvider<ControlTarget> targets) {
        Map<String, ControlTarget> map = new LinkedHashMap<>();
        targets.orderedStream().forEach(target -> target.entities().forEach(entity -> {
            if (map.putIfAbsent(entity, target) != null) {
                throw new IllegalStateException("Two control targets change " + entity);
            }
        }));
        this.byEntity = Map.copyOf(map);
    }

    public Optional<ControlTarget> find(String entity) {
        return entity == null ? Optional.empty() : Optional.ofNullable(byEntity.get(entity));
    }

    /** The entities controlled changes can change, sorted. */
    public Set<String> entities() {
        return new TreeSet<>(byEntity.keySet());
    }
}
