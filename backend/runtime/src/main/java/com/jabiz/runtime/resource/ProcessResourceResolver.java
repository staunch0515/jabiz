package com.jabiz.resource;

import com.jabiz.process.ProcessDefinition;
import com.jabiz.process.ProcessRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Resolves {@code kind = "process"} identifiers to process definitions:
 * {@code urn:<ns>:process:<ProcessName>:<version>}, where the version is a positive integer or
 * the keyword {@code latest}. Example: {@code urn:jabiz:process:SPONSOR_SIGN_IN:1}.
 *
 * Definitions live in memory, so resolution performs no I/O; the Mono only satisfies the
 * {@link ResourceResolver} contract.
 */
@Component
public class ProcessResourceResolver implements ResourceResolver {

    static final String LATEST = "latest";

    private final ProcessRegistry registry;

    public ProcessResourceResolver(ProcessRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String kind() {
        return "process";
    }

    @Override
    public Mono<Resource> resolve(ResourceId id) {
        return Mono.defer(() -> {
            Optional<ProcessDefinition<?, ?, ?>> definition = LATEST.equals(id.id())
                ? registry.findLatest(id.type())
                : registry.find(id.type(), parseVersion(id));
            return definition
                .<Mono<Resource>>map(def -> Mono.just(new ProcessResource(id, def)))
                .orElseGet(() -> Mono.error(new ResourceNotFoundException(
                    "Unregistered process: " + id.type() + " version " + id.id() + " (resource " + id + ")")));
        });
    }

    private static int parseVersion(ResourceId id) {
        try {
            return Integer.parseInt(id.id());
        } catch (NumberFormatException e) {
            throw new InvalidResourceIdException(
                "Process version must be an integer or '" + LATEST + "': " + id);
        }
    }
}
