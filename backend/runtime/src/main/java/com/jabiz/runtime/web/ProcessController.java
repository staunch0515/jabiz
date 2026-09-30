package com.jabiz.runtime.web;

import com.jabiz.context.RequestContext;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.process.ProcessInputs;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.process.ProcessResult;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.security.SensitiveDataMasker;
import com.jabiz.runtime.sod.SodGuard;
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

import java.util.Map;

/**
 * Process API (docs/design/06-process.md section 8): {@code POST /api/processes/{name}/{version|latest}} runs a
 * registered process with the request body as its input. The body is converted to the input record and checked with
 * Bean Validation (400 with all violations); the caller needs every permission the process declares (403). The
 * response carries the output and the operation's {@code processSeqId}. An actor who holds both groups of a
 * segregation-of-duties rule cannot run a process that needs either ({@link SodGuard}, 403 {@code SOD_CONFLICT}).
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
    private final ProcessInputs inputs;
    private final boolean development;
    private final SensitiveDataMasker masker;
    private final SodGuard sod;

    ProcessController(ProcessRegistry registry, ProcessExecutor executor, ProcessInputs inputs,
        Environment environment, SensitiveDataMasker masker, SodGuard sod) {
        this.masker = masker;
        this.sod = sod;
        this.registry = registry;
        this.executor = executor;
        this.inputs = inputs;
        this.development = environment.acceptsProfiles(Profiles.of("dev"));
    }

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
            return sod.check(context, definition.permissions())
                .then(Mono.defer(() -> run(definition, body == null ? Map.of() : body,
                    new ExecutionOptions(idempotencyKey))))
                .map(result -> {
                    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
                    if (result.replayed()) {
                        response.header(REPLAYED, "true");
                    }
                    return response.body(new ExecuteResponse(result.processSeqId(),
                        masker.toJsonWithoutSecrets(result.output())));
                });
        });
    }

    private <I, O, C extends ProcessContext> Mono<ProcessResult<O>> run(
        ProcessDefinition<I, O, C> definition, Map<String, Object> body, ExecutionOptions options
    ) {
        return Mono.defer(() -> executor.run(definition, inputs.convert(definition.inputType(), body), options));
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

    /**
     * The caller needs every permission the process declares. Default deny: a process without permissions runs only
     * in the dev profile (the startup check rejects it elsewhere, but can be turned off).
     */
    static void requirePermissions(ProcessDefinition<?, ?, ?> definition, RequestContext context,
        boolean development) {
        Permissions.requireAll(context, definition.permissions(), development, "Running process " + definition.name());
    }
}
