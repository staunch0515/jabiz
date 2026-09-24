package com.jabiz.resource;

import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Dispatches resource identifiers to the resolver registered for their kind. */
@Component
public class ResourceRegistry {

    private final Map<String, ResourceResolver> resolvers;

    public ResourceRegistry(List<ResourceResolver> allResolvers) {
        Map<String, ResourceResolver> byKind = new LinkedHashMap<>();
        for (ResourceResolver resolver : allResolvers) {
            ResourceResolver previous = byKind.putIfAbsent(resolver.kind(), resolver);
            if (previous != null) {
                throw new IllegalStateException("Duplicate resource resolver for kind '" + resolver.kind()
                    + "': " + previous.getClass().getName() + " and " + resolver.getClass().getName());
            }
        }
        this.resolvers = Map.copyOf(byKind);
    }

    public Mono<Resource> resolve(String urn) {
        return Mono.defer(() -> resolve(ResourceId.parse(urn)));
    }

    public Mono<Resource> resolve(ResourceId id) {
        return Mono.defer(() -> {
            ResourceResolver resolver = resolvers.get(id.kind());
            if (resolver == null) {
                return Mono.error(new ResourceNotFoundException(
                    "No resolver registered for resource kind '" + id.kind() + "' (resource " + id + ")"));
            }
            return resolver.resolve(id);
        });
    }
}
