package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.PRODUCT;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT_DATASET;

/**
 * Marketing copy of a product, written by the demo AI service as an asynchronous external job (decision D38 item 3),
 * the sample applications copy for long calls:
 * <ul>
 *   <li>{@value #REQUEST} ({@code actsOn Product}, {@code commerce.product.write}) starts a job of type
 *       {@value #JOB_TYPE} and answers at once with its id; nothing waits for the service.</li>
 *   <li>The platform runs {@link ProductCopyHandler} outside any transaction, at most two at a time, retried after a
 *       failure (three attempts in all).</li>
 *   <li>With the text, the platform runs {@value #APPLY}: it sets the product's {@code marketingCopy}
 *       ({@code processOnly}) and tells the requester with a task. When the job fails for good, {@value #FAILED}
 *       tells the requester instead.</li>
 * </ul>
 * Both callbacks are {@code internal()} and refuse every caller but the system: {@code internal()} only keeps a process
 * out of the catalog, and anyone running them could set a product's copy to anything (until decision D41).
 */
public final class ProductCopy {

    public static final String JOB_TYPE = "commerce.product-copy";
    public static final String REQUEST = "PRODUCT_COPY_REQUEST";
    public static final String APPLY = "PRODUCT_COPY_APPLY";
    public static final String FAILED = "PRODUCT_COPY_FAILED";
    /** The task type of both notices. */
    public static final String TASK = "commerce.product-copy";
    /** The longest copy kept, the column's size. */
    public static final int MAX_COPY = 2000;
    /** Held by no one: the callbacks are run by the platform only. */
    static final String CALLBACK_PERMISSION = "commerce.product.copy-callback";

    private static final Set<String> LANGUAGES = Set.of("en", "ja", "zh");

    private ProductCopy() {}

    /** What the handler needs: no secrets, it is stored with the job. */
    public record ProductCopyInput(String productId, String sku, String productName, String language) {}

    /** The service's text. */
    public record ProductCopyResult(String text) {}

    /** @param language {@code en}, {@code ja} or {@code zh}; the request's language when absent */
    public record RequestInput(@NotNull UUID productId, @Pattern(regexp = "en|ja|zh") String language) {}

    /** @param jobId follow it at {@code GET /api/external-jobs/{jobId}} */
    public record RequestOutput(String productId, String jobId) {}

    public record ApplyOutput(String productId, String jobId) {}

    public static final ExternalJobType<ProductCopyInput, ProductCopyResult> COPY_JOB = ExternalJobType.define(
        JOB_TYPE, ProductCopyInput.class, ProductCopyResult.class, t -> t
            .handler(ProductCopyHandler.class).services(ModerateText.SERVICE).timeout(Duration.ofSeconds(90))
            .retry(3, Duration.ofSeconds(10), Duration.ofMinutes(10)).maxConcurrency(2)
            .onCompleted(APPLY).onFailed(FAILED));

    private static final String PRODUCT_ID = "productId";
    private static final String PRODUCT_KEY = "product";
    private static final String LANGUAGE = "language";
    private static final String JOB_ID = "jobId";
    private static final String INPUT = "input";

    public static final ProcessDefinition<RequestInput, RequestOutput, ProcessContext> REQUEST_PROCESS =
        ProcessDefinition.define(REQUEST, 1, RequestInput.class, RequestOutput.class, ProcessContext.class, pb -> pb
            .description("Asks the AI service for a product's marketing copy; the copy arrives later.")
            .permissions("commerce.product.write")
            .actsOn(PRODUCT, PRODUCT_ID)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(PRODUCT_ID, input.productId());
                String language = input.language() != null ? input.language()
                    : start.request().locale().getLanguage().toLowerCase(Locale.ROOT);
                ctx.put(LANGUAGE, LANGUAGES.contains(language) ? language : "en");
                return ctx;
            })
            .outputMapper(ctx -> new RequestOutput(String.valueOf(ctx.get(PRODUCT_ID)), ctx.get(JOB_ID, String.class)))
            .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
            .step("Start the job", StartExternalJob.<ProcessContext>of(JOB_TYPE, ProductCopy::jobInput)
                .reference(ctx -> String.valueOf(ctx.get(PRODUCT_ID)))
                .outputKey(JOB_ID)));

    static ProductCopyInput jobInput(ProcessContext ctx) {
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        return new ProductCopyInput(String.valueOf(product.id()), product.get("sku"), product.get("productName"),
            ctx.get(LANGUAGE, String.class));
    }

    public static final ProcessDefinition<ExternalJobCompletion, ApplyOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ExternalJobCompletion.class, ApplyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Saves the marketing copy the AI service wrote, and tells the requester.")
                .permissions(CALLBACK_PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ApplyOutput(String.valueOf(ctx.get(PRODUCT_ID)),
                    ctx.get(INPUT, ExternalJobCompletion.class).jobId()))
                .compute("Refuse other callers", (metadata, ctx) -> requireSystem(ctx))
                .compute("Find the product", (metadata, ctx) -> ctx.put(PRODUCT_ID,
                    UUID.fromString(ctx.get(INPUT, ExternalJobCompletion.class).reference())))
                .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
                .compute("Save the copy", (metadata, ctx) -> apply(ctx))
                .step("Tell the requester", CreateTask.when(ProductCopy::hasPerson, ctx -> notice(ctx,
                    ctx.get(INPUT, ExternalJobCompletion.class).requestedBy(),
                    ctx.get(INPUT, ExternalJobCompletion.class).jobId(), "task.commerce.product-copy.ready",
                    Map.of()))));

    public static final ProcessDefinition<ExternalJobFailure, ApplyOutput, ProcessContext> FAILED_PROCESS =
        ProcessDefinition.define(FAILED, 1, ExternalJobFailure.class, ApplyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Tells the requester that the AI service could not write the copy.")
                .permissions(CALLBACK_PERMISSION)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(ctx -> new ApplyOutput(String.valueOf(ctx.get(PRODUCT_ID)),
                    ctx.get(INPUT, ExternalJobFailure.class).jobId()))
                .compute("Refuse other callers", (metadata, ctx) -> requireSystem(ctx))
                .compute("Find the product", (metadata, ctx) -> ctx.put(PRODUCT_ID,
                    UUID.fromString(ctx.get(INPUT, ExternalJobFailure.class).reference())))
                .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
                .step("Tell the requester", CreateTask.when(ProductCopy::hasPerson, ctx -> notice(ctx,
                    ctx.get(INPUT, ExternalJobFailure.class).requestedBy(),
                    ctx.get(INPUT, ExternalJobFailure.class).jobId(), "task.commerce.product-copy.failed",
                    Map.of("error", ctx.get(INPUT, ExternalJobFailure.class).errorCode())))));

    /** Only the platform hands results over: the process API, even for an administrator, does not. */
    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(CALLBACK_PERMISSION,
                "Marketing copy is saved from the AI service's completed jobs only");
        }
    }

    static void apply(ProcessContext ctx) {
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        String text = ctx.get(INPUT, ExternalJobCompletion.class).result(ProductCopyResult.class).text();
        String copy = text == null ? "" : text.strip();
        if (copy.length() > MAX_COPY) {
            copy = copy.substring(0, MAX_COPY);
        }
        if (!copy.equals(product.get("marketingCopy"))) {
            Map<String, Object> changes = new LinkedHashMap<>();
            changes.put("marketingCopy", copy.isEmpty() ? null : copy);
            ctx.changes().update(PRODUCT, product.id(), product.version(), changes);
        }
    }

    /** A job started by the system (a scheduled job, say) has nobody to tell. */
    static boolean hasPerson(ProcessContext ctx) {
        Object input = ctx.get(INPUT);
        String requester = input instanceof ExternalJobCompletion c ? c.requestedBy()
            : ((ExternalJobFailure) input).requestedBy();
        return !RequestContext.SYSTEM_ACTOR.equals(requester);
    }

    static TaskSpec notice(ProcessContext ctx, String requester, String jobId, String titleKey,
        Map<String, String> extra) {
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("sku", product.get("sku"));
        params.putAll(extra);
        return TaskSpec.forUser(TASK, titleKey, params, requester).about(PRODUCT, product.id())
            .source("product-copy:" + jobId);
    }
}
