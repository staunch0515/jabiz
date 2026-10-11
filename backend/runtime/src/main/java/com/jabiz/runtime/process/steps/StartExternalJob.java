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
import com.jabiz.security.Sensitive;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
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
 * <p>The input is stored as JSON: one holding a secret (a value of a {@code @Sensitive} record component, at any depth)
 * is refused with 422 {@code EXTERNAL_JOB_INPUT_SECRET}, and one larger than {@value #MAX_INPUT_BYTES} bytes with
 * {@code EXTERNAL_JOB_INPUT_TOO_LARGE}. Handlers read secrets from their services instead. Starts beyond the type's
 * {@code maxOpenPerRequester}, or a second open job of a reference when the type allows {@code oneOpenPerReference},
 * are refused with 422 {@code EXTERNAL_JOB_LIMIT}.
 */
@Component
public class StartExternalJob<C extends ProcessContext> implements StepHandler<StartExternalJob.Metadata<C>, C>,
    CheckedStep<StartExternalJob.Metadata<C>> {

    /** The largest input stored, in bytes of JSON. */
    public static final int MAX_INPUT_BYTES = 64 * 1024;
    /** The longest reference. */
    public static final int MAX_REFERENCE = 200;
    private static final int MAX_DEPTH = 16;

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
    private final JsonMapper json;

    public StartExternalJob(ExternalJobRegistry jobs, ExternalJobRecorder recorder, JsonMapper json) {
        this.jobs = jobs;
        this.recorder = recorder;
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
            if (reference == null && type.oneOpenPerReference()) {
                return Mono.error(new IllegalStateException("External job type " + type.name()
                    + " allows one open job per reference: the step needs a reference"));
            }
            Optional<String> secret = sensitivePath(input, "", 0);
            if (secret.isPresent()) {
                // The path names the component, never its value.
                return Mono.error(new BusinessRuleViolationException(new Violation(secret.get(),
                    PlatformErrorCodes.EXTERNAL_JOB_INPUT_SECRET, "The input of external job " + type.name()
                    + " holds a secret", Map.of("jobType", type.name(), "field", secret.get()))));
            }
            String text = json.writeValueAsString(input);
            if (text.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.EXTERNAL_JOB_INPUT_TOO_LARGE, "The input of external job " + type.name()
                    + " is too large", Map.of("jobType", type.name(), "max", MAX_INPUT_BYTES))));
            }
            String requester = ctx.request().actorId();
            return withinLimits(type, reference, requester)
                .then(recorder.insertJob(type.name(), text, reference, requester, ctx.processSeqId(), ctx.opTime()))
                .doOnNext(id -> {
                    if (metadata.outputKey() != null) {
                        ctx.put(metadata.outputKey(), id);
                    }
                })
                .then();
        });
    }

    /**
     * Refuses a start beyond the type's limits with 422 {@code EXTERNAL_JOB_LIMIT}. Each limit is counted under an
     * advisory lock of its own, held to the end of the transaction, so that concurrent starts are counted one after
     * the other; the reference's lock is always taken before the requester's, so they cannot deadlock.
     */
    private Mono<Void> withinLimits(ExternalJobType<?, ?> type, String reference, String requester) {
        Mono<Void> perReference = !type.oneOpenPerReference() ? Mono.empty()
            : recorder.lockStarts(type.name(), "reference", reference)
                .then(recorder.openJobsOfReference(type.name(), reference))
                .flatMap(open -> open >= 1 ? Mono.error(limit(type, 1, "REFERENCE")) : Mono.empty());
        Mono<Void> perRequester = recorder.lockStarts(type.name(), "requester", requester)
            .then(recorder.openJobsOfRequester(type.name(), requester))
            .flatMap(open -> open >= type.maxOpenPerRequester()
                ? Mono.error(limit(type, type.maxOpenPerRequester(), "REQUESTER")) : Mono.empty());
        return perReference.then(perRequester);
    }

    private static BusinessRuleViolationException limit(ExternalJobType<?, ?> type, int limit, String scope) {
        return new BusinessRuleViolationException(new Violation(null, PlatformErrorCodes.EXTERNAL_JOB_LIMIT,
            "Too many open external jobs of type " + type.name(), Map.of("jobType", type.name(), "limit", limit,
            "scope", scope)));
    }

    /**
     * The path of the first value of a {@link Sensitive} record component, at any depth (records, collections, maps
     * and arrays, values typed {@code Object} included); empty when there is none. The declaration decides, not the
     * name: {@code maxTokens} is no secret. Values of masked entity fields are not looked for: an input is a record of
     * its own, whose components are not entity fields, and matching their names would refuse innocent ones; the input
     * is shown by no interface (decision D38, docs/design/11-ledger-events-jobs.md section 4a).
     */
    static Optional<String> sensitivePath(Object value, String prefix, int depth) {
        if (value == null || depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (value instanceof Record record) {
            for (RecordComponent component : record.getClass().getRecordComponents()) {
                Object item;
                try {
                    Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    item = accessor.invoke(record);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    continue;
                }
                String path = prefix + component.getName();
                if (component.isAnnotationPresent(Sensitive.class) && item != null) {
                    return Optional.of(path);
                }
                Optional<String> nested = sensitivePath(item, path + ".", depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
            return Optional.empty();
        }
        String at = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Optional<String> nested = sensitivePath(entry.getValue(), at + "[" + entry.getKey() + "].",
                    depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (value instanceof Iterable<?> items) {
            int i = 0;
            for (Object item : items) {
                Optional<String> nested = sensitivePath(item, at + "[" + i++ + "].", depth + 1);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        } else if (value instanceof Object[] array) {
            for (int i = 0; i < array.length; i++) {
                Optional<String> nested = sensitivePath(array[i], at + "[" + i + "].", depth + 1);
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
