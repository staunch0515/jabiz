package com.jabiz.runtime.resource;

import com.jabiz.resource.Resource;
import com.jabiz.resource.ResourceId;
import reactor.core.publisher.Mono;

/** Resolves resources of one kind. Exactly one resolver may be registered per kind. */
public interface ResourceResolver {

    /** The resource kind this resolver is responsible for, for example "entity". */
    String kind();

    /**
     * Resolves the resource.
     *
     * @return the resource, or an error signal of {@link ResourceNotFoundException} if it does not exist
     */
    Mono<Resource> resolve(ResourceId id);
}
