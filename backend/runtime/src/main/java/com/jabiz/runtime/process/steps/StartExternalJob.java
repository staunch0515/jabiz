package com.jabiz.runtime.process.steps;

import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobType;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSource;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.security.SensitiveDataMasker;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Starts an asynchronous external job (decision D38 item 3): {@code .step("Write the copy",
 * StartExternalJob.of("commerce.product-copy", ctx -> new ProductCopyInput(…)).reference(ctx -> "product:" + id)
 * .outputKey("jobId"))}. The job is inserted in the process's transaction, with the actor of the request as its
 * requester and the operation's number, and its id goes to the context at once: the work itself runs later, outside
 * any transaction, on the platform's executor. A rollback or a dry run leaves no job; a request replayed by its
 * idempotency key returns the first job's id, since the output is replayed.
 *
 * <p>The input is stored as JSON: one holding a secret (a value under a {@code @Sensitive} or secret-like name) is
 * refused with 422 {@code EXTERNAL_JOB_INPUT_SECRET}, and one larger than {@value #MAX_INPUT_BYTES} bytes with
 * {@code EXTERNAL_JOB_INPUT_TOO_LARGE}. Handlers read secrets from their services instead.
 */
@Component
public class StartExternalJob<C extends ProcessContext> implements StepHandler<StartExternalJob.Metadata<C>, C>,
    CheckedStep<StartExternalJob.Metadata<C>> {

    /** The largest input stored, in bytes of JSON. */
    public static final int MAX_INPUT_BYTES = 64 * 1024;
    /** The longest reference. */
    public static final int MAX_REFERENCE = 200;

    /**
     * @param jobType   the declared job type
     * @param input     builds the input, an instance of the type's input record
     * @param reference what the job is about, for the requester and the completion process (at most 200
     *                  characters); null for none
     * @param outputKey context key receiving the job's id; null to discard it
     * @param when      whether to start a job at all; always when null
     */
    public record Metadata<C>(String jobType, Function<C, ?> input, Function<C, String> reference, String outputKey,
        Predicate<C> when) {
        public Metadata {
            Objects.requireNonNull(jobType, "jobType must not be null");
            Objects.requireNonNull(input, "input must not be null");
        }
    }

    /**
     * A use of the step under construction: a process builder takes it as it is ({@link StepSource}); each method
     * returns a new one.
     */
    public static final class Spec<C extends ProcessContext> implements StepSource<Metadata<C>, C> {

        private final Metadata<C> metadata;

        private Spec(Metadata<C> metadata) {
            this.metadata = metadata;
        }

        @Override
        public StepSpec<Metadata<C>, C> toStepSpec() {
            return StepSpec.of(StartExternalJob.class, metadata);
        }

        Metadata<C> metadata() {
            return metadata;
        }

        /** What the job is about (a product, an order), kept with it: at most 200 characters. */
        public Spec<C> reference(Function<C, String> reference) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), reference, m.outputKey(), m.when()));
        }

        /** Where the job's id goes in the context. */
        public Spec<C> outputKey(String key) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), m.reference(), key, m.when()));
        }

        /** Starts the job only when {@code condition} holds on the context; otherwise the step does nothing. */
        public Spec<C> when(Predicate<C> condition) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), m.reference(), m.outputKey(),
                Objects.requireNonNull(condition, "condition must not be null")));
        }
    }

    public static <C extends ProcessContext> Spec<C> of(String jobType, Function<C, ?> input) {
        return new Spec<>(new Metadata<>(jobType, input, null, null, null));
    }

    private final ExternalJobRegistry jobs;
    private final ExternalJobRecorder recorder;
    private final SensitiveDataMasker masker;
    private final JsonMapper json;

    public StartExternalJob(ExternalJobRegistry jobs, ExternalJobRecorder recorder, SensitiveDataMasker masker,
        JsonMapper json) {
        this.jobs = jobs;
        this.recorder = recorder;
        this.masker = masker;
        this.json = json;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (metadata.when() != null && !metadata.when().test(ctx)) {
                return Mono.empty();
            }
            ExternalJobType<?, ?> type = jobs.find(metadata.jobType()).orElseThrow(() -> new IllegalStateException(
                "No external job type " + metadata.jobType() + " is declared"));
            Object input = metadata.input().apply(ctx);
            if (!type.inputType().isInstance(input)) {
                return Mono.error(new IllegalStateException("External job type " + type.name() + " takes "
                    + type.inputType().getName() + " but was given "
                    + (input == null ? "null" : input.getClass().getName())));
            }
            String reference = metadata.reference() == null ? null : metadata.reference().apply(ctx);
            if (reference != null && (reference.isBlank() || reference.length() > MAX_REFERENCE)) {
                return Mono.error(new IllegalArgumentException("The reference of an external job is 1 to "
                    + MAX_REFERENCE + " characters"));
            }
            JsonNode tree = json.valueToTree(input);
            Optional<String> secret = secretPath(tree, "");
            if (secret.isPresent()) {
                // The path names the property, never its value.
                return Mono.error(new BusinessRuleViolationException(new Violation(secret.get(),
                    PlatformErrorCodes.EXTERNAL_JOB_INPUT_SECRET, "The input of external job " + type.name()
                    + " holds a secret", Map.of("jobType", type.name(), "field", secret.get()))));
            }
            String text = json.writeValueAsString(tree);
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.EXTERNAL_JOB_INPUT_TOO_LARGE, "The input of external job " + type.name()
                    + " is too large", Map.of("jobType", type.name(), "max", MAX_INPUT_BYTES))));
            }
            return recorder.insertJob(type.name(), text, reference, ctx.request().actorId(), ctx.processSeqId(),
                    ctx.opTime())
                .doOnNext(id -> {
                    if (metadata.outputKey() != null) {
                        ctx.put(metadata.outputKey(), id);
                    }
                })
                .then();
        });
    }

    /** The path of the first value held under a secret name, at any depth; empty when there is none. */
    private Optional<String> secretPath(JsonNode node, String prefix) {
        if (node == null) {
            return Optional.empty();
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                String path = prefix + entry.getKey();
                JsonNode value = entry.getValue();
                if (masker.isSensitive(entry.getKey()) && value != null && !value.isNull()) {
                    return Optional.of(path);
                }
                Optional<String> nested = secretPath(value, path + ".");
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                Optional<String> nested = secretPath(node.get(i), prefix.isEmpty() ? "[" + i + "]."
                    : prefix.substring(0, prefix.length() - 1) + "[" + i + "].");
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        return jobs.find(metadata.jobType()).isPresent() ? List.of()
            : List.of("starts undeclared external job type " + metadata.jobType());
    }
}
