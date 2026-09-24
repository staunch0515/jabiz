package com.jabiz.resource;

import com.jabiz.process.ProcessDefinition;

/**
 * Resource carrying a full, strongly typed {@link ProcessDefinition}. Consumers that only need
 * display data can use {@code definition().name()} and {@code definition().version()}; those
 * that need more can walk {@code definition().steps()}.
 */
public record ProcessResource(ResourceId resourceId, ProcessDefinition<?, ?, ?> definition) implements Resource {}
