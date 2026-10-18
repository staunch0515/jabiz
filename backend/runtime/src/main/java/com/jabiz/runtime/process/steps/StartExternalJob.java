package com.jabiz.runtime.process.steps;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobType;
import com.jabiz.file.FilePolicy;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSource;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.file.FilePermissions;
import com.jabiz.runtime.file.FilePolicyRegistry;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.external.ExternalJobRecorder;
import com.jabiz.runtime.external.ExternalJobRegistry;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.security.Sensitive;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
 * is refused with 422 {@code EXTERNAL_JOB_INPUT_SECRET}, and one larger than the type's {@code maxInputSize}
 * ({@value #MAX_INPUT_BYTES} bytes unless declared, decision D50 item 1) with {@code EXTERNAL_JOB_INPUT_TOO_LARGE}.
 * Handlers read secrets from their services instead. Starts beyond the type's {@code maxOpenPerRequester}, or a second
 * open job of a reference when the type allows {@code oneOpenPerReference}, are refused with 422
 * {@code EXTERNAL_JOB_LIMIT}.
 *
 * <p>Files (decision D50): the files the job reads ({@link Spec#files}) are checked against what the requester may
 * read and recorded with the job; a type that produces files may be started only by someone who may upload under its
 * output policy (403 otherwise), since the files become that person's uploads.
 */
@Component
public class StartExternalJob<C extends ProcessContext> implements StepHandler<StartExternalJob.Metadata<C>, C>,
    CheckedStep<StartExternalJob.Metadata<C>> {

    /** The largest input stored unless the type declares otherwise, in bytes of JSON. */
    public static final int MAX_INPUT_BYTES = ExternalJobType.DEFAULT_MAX_INPUT_BYTES;
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
     * @param files     the ids of the files the job reads (decision D50 item 2); none when null
     */
    public record Metadata<C>(String jobType, Function<C, ?> input, Function<C, String> reference, String outputKey,
        Predicate<C> when, Function<C, ? extends Collection<UUID>> files) {
        public Metadata {
            Objects.requireNonNull(jobType, "jobType must not be null");
            Objects.requireNonNull(input, "input must not be null");
        }

        /** A start without files, as decision D38 had it. */
        public Metadata(String jobType, Function<C, ?> input, Function<C, String> reference, String outputKey,
            Predicate<C> when) {
            this(jobType, input, reference, outputKey, when, null);
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
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), reference, m.outputKey(), m.when(), m.files()));
        }

        /** Where the job's id goes in the context. */
        public Spec<C> outputKey(String key) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), m.reference(), key, m.when(), m.files()));
        }

        /** Starts the job only when {@code condition} holds on the context; otherwise the step does nothing. */
        public Spec<C> when(Predicate<C> condition) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), m.reference(), m.outputKey(),
                Objects.requireNonNull(condition, "condition must not be null"), m.files()));
        }

        /**
         * The files the job reads (decision D50 item 2), in order, for a type that declares {@code inputFiles}: each
         * must exist, be of one of the type's policies and be readable by the requester ({@code file.read} or the
         * policy's read permission), or the start is refused with 422 {@code EXTERNAL_JOB_INPUT_FILE}; more files,
         * or a larger one, than the type reads with {@code EXTERNAL_JOB_INPUT_FILE_LIMIT}. A file named twice is read
         * once.
         */
        public Spec<C> files(Function<C, ? extends Collection<UUID>> fileIds) {
            Metadata<C> m = metadata();
            return new Spec<>(new Metadata<>(m.jobType(), m.input(), m.reference(), m.outputKey(), m.when(),
                Objects.requireNonNull(fileIds, "fileIds must not be null")));
        }
    }

    public static <C extends ProcessContext> Spec<C> of(String jobType, Function<C, ?> input) {
        return new Spec<>(new Metadata<>(jobType, input, null, null, null, null));
    }

    private final ExternalJobRegistry jobs;
    private final ExternalJobRecorder recorder;
    private final JsonMapper json;
    private final FilePolicyRegistry policies;

    /**
     * The step as decision D38 built it, without file policies: a type with files cannot be started by it (its
     * policies are not found). Kept for code of line 1.2 that builds the step itself (decision D50 item 6).
     */
    public StartExternalJob(ExternalJobRegistry jobs, ExternalJobRecorder recorder, JsonMapper json) {
        this(jobs, recorder, json, new FilePolicyRegistry(new StaticListableBeanFactory()
            .getBeanProvider(FilePolicy.class)));
    }

    @Autowired
    public StartExternalJob(ExternalJobRegistry jobs, ExternalJobRecorder recorder, JsonMapper json,
        FilePolicyRegistry policies) {
        this.jobs = jobs;
        this.recorder = recorder;
        this.json = json;
        this.policies = policies;
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
            if (text.getBytes(StandardCharsets.UTF_8).length > type.maxInputBytes()) {
                return Mono.error(new BusinessRuleViolationException(new Violation(null,
                    PlatformErrorCodes.EXTERNAL_JOB_INPUT_TOO_LARGE, "The input of external job " + type.name()
                    + " is too large", Map.of("jobType", type.name(), "max", type.maxInputBytes()))));
            }
            List<UUID> fileIds = fileIds(metadata, type, ctx);
            RequestContext request = ctx.request();
            if (type.outputFiles() != null) {
                // The files it produces become the requester's uploads under the policy (decision D50 item 3): only
                // someone who may upload there may start it.
                FilePolicy policy = policies.find(type.outputFiles().policy()).orElseThrow(() ->
                    new IllegalStateException("External job type " + type.name() + " stores its files under "
                        + "undeclared file policy " + type.outputFiles().policy()));
                if (!isSystem(request)) {
                    Permissions.require(request, policy.uploadPermission(), "Starting external job " + type.name()
                        + ", whose files are stored under " + policy.name());
                }
            }
            String requester = request.actorId();
            return withinLimits(type, reference, requester)
                .then(Flux.fromIterable(fileIds).concatMap(fileId -> readable(type, request, fileId)).collectList())
                .flatMap(files -> recorder.insertJob(type.name(), text, reference, requester, ctx.processSeqId(),
                        ctx.opTime())
                    .flatMap(id -> Flux.range(0, files.size())
                        .concatMap(i -> recorder.insertInputFile(id, i, files.get(i), ctx.opTime(),
                            policies.find(files.get(i).policy()).map(FilePolicy::privateMetadata).orElse(false)))
                        .then(Mono.just(id))))
                .doOnNext(id -> {
                    if (metadata.outputKey() != null) {
                        ctx.put(metadata.outputKey(), id);
                    }
                })
                .then();
        });
    }

    /** The files the job is to read, each once, in order; refused beyond the type's count. */
    private static <C extends ProcessContext> List<UUID> fileIds(Metadata<C> metadata, ExternalJobType<?, ?> type,
        C ctx) {
        if (metadata.files() == null) {
            return List.of();
        }
        if (type.inputFiles() == null) {
            throw new IllegalStateException("External job type " + type.name() + " reads no files: declare "
                + "inputFiles to give it some");
        }
        Collection<UUID> given = metadata.files().apply(ctx);
        Set<UUID> ids = new LinkedHashSet<>();
        if (given != null) {
            for (UUID id : given) {
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        if (ids.size() > type.inputFiles().maxFiles()) {
            throw fileLimit(type);
        }
        return List.copyOf(ids);
    }

    /**
     * The file, if the requester may give it to the job: it exists, is of one of the type's policies, and the
     * requester holds {@code file.read} or its policy's read permission, as reading it through {@code /api/files}
     * would require. All three refusals are one answer, so that a start tells nothing about files the requester may
     * not see; the size is checked only then. The system (an event consumer, a scheduled job) may give any file.
     */
    private Mono<ExternalJobRecorder.StoredFile> readable(ExternalJobType<?, ?> type, RequestContext request,
        UUID fileId) {
        BusinessRuleViolationException refused = new BusinessRuleViolationException(new Violation(null,
            PlatformErrorCodes.EXTERNAL_JOB_INPUT_FILE, "File " + fileId + " cannot be given to external job "
            + type.name(), Map.of("jobType", type.name(), "fileId", fileId.toString())));
        return recorder.lockStoredFile(fileId)
            .switchIfEmpty(Mono.error(refused))
            .flatMap(file -> {
                boolean mayRead = isSystem(request) || request.hasPermission(FilePermissions.READ)
                    || policies.find(file.policy()).map(FilePolicy::readPermission).map(request::hasPermission)
                        .orElse(false);
                if (!mayRead || !type.inputFiles().policies().contains(file.policy())) {
                    return Mono.error(refused);
                }
                if (file.sizeBytes() > type.inputFiles().maxBytes()) {
                    return Mono.error(fileLimit(type));
                }
                return Mono.just(file);
            });
    }

    private static BusinessRuleViolationException fileLimit(ExternalJobType<?, ?> type) {
        return new BusinessRuleViolationException(new Violation(null,
            PlatformErrorCodes.EXTERNAL_JOB_INPUT_FILE_LIMIT, "External job " + type.name() + " is given more files, "
            + "or a larger one, than it reads", Map.of("jobType", type.name(), "maxFiles",
            type.inputFiles().maxFiles(), "maxBytes", type.inputFiles().maxBytes())));
    }

    private static boolean isSystem(RequestContext request) {
        return RequestContext.SYSTEM_ACTOR.equals(request.actorId());
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
        Optional<ExternalJobType<?, ?>> type = jobs.find(metadata.jobType());
        if (type.isEmpty()) {
            return List.of("starts undeclared external job type " + metadata.jobType());
        }
        if (metadata.files() != null && type.get().inputFiles() == null) {
            return List.of("gives files to external job type " + metadata.jobType() + ", which declares no "
                + "inputFiles");
        }
        return List.of();
    }
}
