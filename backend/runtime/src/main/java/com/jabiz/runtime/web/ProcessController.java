package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Process API (docs/design/06-process.md section 8): {@code POST /api/processes/{name}/{version|latest}} runs a
 * registered process with the request body as its input. The body is converted to the input record and checked with
 * Bean Validation (400 with all violations); the caller needs every permission the process declares (403). The
 * response carries the output and the operation's {@code processSeqId}.
 *
 * <p>With an {@code Idempotency-Key} header, a repeated request of the same actor returns the first result without
 * running again, flagged by the response header {@code Idempotency-Replayed: true} (decision D4).
 */
@RestController
@RequestMapping("/api/processes")
class ProcessController {

    record ExecuteResponse(long processSeqId, Object output) {}

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";
    static final String REPLAYED = "Idempotency-Replayed";

    private final ProcessRegistry registry;
    private final ProcessExecutor executor;
    private final Validator validator;
    private final JsonMapper json;
    private final boolean development;

    ProcessController(ProcessRegistry registry, ProcessExecutor executor, Validator validator, JsonMapper json,
        Environment environment) {
        this.registry = registry;
        this.executor = executor;
        this.validator = validator;
        this.json = json;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
        // The first validation loads message bundles from the classpath; do it here rather than on an event loop.
        validator.validate(new Warmup(null));
    }

    private record Warmup(@jakarta.validation.constraints.NotNull String value) {}

    @PostMapping("/{name}/{version}")
    Mono<ResponseEntity<ExecuteResponse>> execute(
        @PathVariable String name,
        @PathVariable String version,
        @RequestBody(required = false) Map<String, Object> body,
        @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey
    ) {
        return RequestContexts.current().flatMap(context -> {
            ProcessDefinition<?, ?, ?> definition = find(name, version);
            requirePermissions(definition, context, development);
            return run(definition, body == null ? Map.of() : body, new ExecutionOptions(idempotencyKey))
                .map(result -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
                    if (result.replayed()) {
                        response.header(REPLAYED, "true");
                    }
                    return response.body(new ExecuteResponse(result.processSeqId(), result.output()));
                });
        });
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> run(
        ProcessDefinition<I, O, C> definition, Map<String, Object> body, ExecutionOptions options
    ) {
        return Mono.defer(() -> executor.run(definition, input(definition.inputType(), body), options));
    }

    private ProcessDefinition<?, ?, ?> find(String name, String version) {
        if ("latest".equals(version)) {
            return registry.findLatest(name)
                .orElseThrow(() -> new EntityNotFoundException("Unknown process " + name));
        }
        int number;
        try {
            number = Integer.parseInt(version);
        } catch (NumberFormatException e) {
            throw ListRequests.invalid("version", "version must be a number or 'latest'");
        }
        return registry.find(name, number)
            .orElseThrow(() -> new EntityNotFoundException("Unknown process " + name + " version " + number));
    }

    private <I> I input(Class<I> type, Map<String, Object> body) {
        I input;
        try {
            input = json.convertValue(body, type);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.INVALID_VALUE,
                "The request body is not a valid input of this process")));
        }
        if (input == null) {
            throw new ValidationException(List.of(new Violation(null, PlatformErrorCodes.REQUIRED,
                "The process needs an input")));
        }
        Set<ConstraintViolation<I>> broken = validator.validate(input);
        if (!broken.isEmpty()) {
            throw new ValidationException(broken.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(ProcessController::toViolation)
                .toList());
        }
        return input;
    }

    private static Violation toViolation(ConstraintViolation<?> violation) {
        String constraint = violation.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName();
        String code = switch (constraint) {
            case "NotNull", "NotBlank", "NotEmpty" -> PlatformErrorCodes.REQUIRED;
            default -> PlatformErrorCodes.INVALID_VALUE;
        };
        String field = violation.getPropertyPath().toString();
        return new Violation(field.isEmpty() ? null : field, code, field + " " + violation.getMessage(),
            Map.of("constraint", constraint));
    }

    /**
     * The caller needs every permission the process declares. Default deny: a process without permissions runs only
     * in the dev profile (the startup check rejects it elsewhere, but can be turned off).
     */
    static void requirePermissions(ProcessDefinition<?, ?, ?> definition, RequestContext context,
        boolean development) {
        if (definition.permissions().isEmpty() && !development) {
            throw new PermissionDeniedException("-", "Process " + definition.name() + " declares no permissions");
        }
        for (String permission : definition.permissions().stream().sorted().toList()) {
            if (!context.hasPermission(permission)) {
                throw new PermissionDeniedException(permission,
                    "Running process " + definition.name() + " requires permission " + permission);
            }
        }
    }
}
