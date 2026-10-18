package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import com.jabiz.external.ExternalJobCompletion;
import com.jabiz.external.ExternalJobFailure;
import com.jabiz.external.ExternalJobType;
import com.jabiz.external.ExternalService;
import com.jabiz.file.FilePolicy;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.StartExternalJob;
import com.jabiz.runtime.task.CreateTask;
import com.jabiz.runtime.task.TaskSpec;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.PRODUCT;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT_DATASET;

/**
 * A product photo redone by the demo AI service (decision D50), the sample application's copy for external jobs with
 * files:
 * <ul>
 *   <li>{@value #REQUEST} ({@code actsOn Product}, {@code commerce.product.write}) starts a job of type
 *       {@value #JOB_TYPE} that reads the product's photo ({@code inputFiles}): the requester must be allowed to read
 *       it ({@code commerce.media.read}), and since the new photo becomes the requester's upload, to upload product
 *       photos ({@code commerce.media.upload}). A product without a photo is refused.</li>
 *   <li>{@link ProductImageHandler} sends the photo to the service and returns the image it answers with as a file
 *       ({@code outputFiles}); the platform stores it as a {@value CommerceFiles#IMAGE} upload of the requester,
 *       through the same checks as {@code /api/files}: an answer that is no JPEG or PNG fails the job, and the image
 *       is re-encoded without its metadata.</li>
 *   <li>{@value #APPLY} puts the new photo on the product and tells the requester; the old photo, no longer referred
 *       to, goes with the file sweep. When the product shows another photo by then (someone changed it meanwhile),
 *       the new one is not applied and the requester is told so. {@value #FAILED} tells the requester the job
 *       failed.</li>
 * </ul>
 */
public final class ProductImage {

    public static final String JOB_TYPE = "commerce.product-image";
    public static final String REQUEST = "PRODUCT_IMAGE_REQUEST";
    public static final String APPLY = "PRODUCT_IMAGE_APPLY";
    public static final String FAILED = "PRODUCT_IMAGE_FAILED";
    /** The task type of both notices. */
    public static final String TASK = "commerce.product-image";
    /** The key of the image in the job's result. */
    public static final String IMAGE = "image";
    /** Held by no one: the callbacks are run by the platform only. */
    static final String CALLBACK_PERMISSION = "commerce.product.image-callback";
    /** The largest photo sent and received. */
    static final long MAX_IMAGE_BYTES = 10 * FilePolicy.MB;

    private ProductImage() {}

    /** What the handler needs besides the photo, which it reads as the job's file. */
    public record ProductImageInput(String productId, String sku, String style) {}

    /**
     * The style the service drew in, and the photo it was made from; the image itself is the job's file
     * {@value #IMAGE}.
     */
    public record ProductImageResult(String style, UUID sourceFileId) {}

    /**
     * The service's images: the demo AI service's address and key, with room for an image in the answer (base64 of
     * up to {@value #MAX_IMAGE_BYTES} bytes). A service of its own, so that the moderation of product names in the
     * request path keeps the small answers of {@value ModerateText#SERVICE}.
     */
    public static final String SERVICE = "demo-ai-images";

    public static final ExternalService DEMO_AI_IMAGES = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://ai.example.com/v1").allowedHosts("ai.example.com")
        .secret("apiKey", "JABIZ_DEMO_AI_KEY")
        .connectTimeout(Duration.ofSeconds(5)).requestTimeout(Duration.ofSeconds(60))
        .idempotencyHeader("Idempotency-Key").maxResponseSize(ExternalService.MAX_RESPONSE_SIZE));

    /** @param style {@code studio} (the default) or {@code white} */
    public record RequestInput(@NotNull UUID productId, @Pattern(regexp = "studio|white") String style) {}

    /** @param jobId follow it at {@code GET /api/external-jobs/{jobId}} */
    public record RequestOutput(String productId, String jobId) {}

    public record ApplyOutput(String productId, String jobId) {}

    public static final ExternalJobType<ProductImageInput, ProductImageResult> IMAGE_JOB = ExternalJobType.define(
        JOB_TYPE, ProductImageInput.class, ProductImageResult.class, t -> t
            .handler(ProductImageHandler.class).services(SERVICE).timeout(Duration.ofSeconds(90))
            .retry(3, Duration.ofSeconds(10), Duration.ofMinutes(10)).maxConcurrency(2).oneOpenPerReference()
            .inputFiles(1, MAX_IMAGE_BYTES, CommerceFiles.IMAGE)
            .outputFiles(CommerceFiles.IMAGE, 1, MAX_IMAGE_BYTES)
            .onCompleted(APPLY).onFailed(FAILED));

    private static final String PRODUCT_ID = "productId";
    private static final String PRODUCT_KEY = "product";
    private static final String STYLE = "style";
    private static final String JOB_ID = "jobId";
    private static final String INPUT = "input";
    private static final String PHOTO = "imageFileId";
    private static final String APPLIED = "applied";

    public static final ProcessDefinition<RequestInput, RequestOutput, ProcessContext> REQUEST_PROCESS =
        ProcessDefinition.define(REQUEST, 1, RequestInput.class, RequestOutput.class, ProcessContext.class, pb -> pb
            .description("Asks the AI service to redo a product's photo; the new photo arrives later.")
            .permissions("commerce.product.write")
            .actsOn(PRODUCT, PRODUCT_ID)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(PRODUCT_ID, input.productId());
                ctx.put(STYLE, input.style() == null ? "studio" : input.style());
                return ctx;
            })
            .outputMapper(ctx -> new RequestOutput(String.valueOf(ctx.get(PRODUCT_ID)), ctx.get(JOB_ID, String.class)))
            .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
            .compute("Require a photo", (metadata, ctx) -> {
                if (ctx.get(PRODUCT_KEY, EntityInstance.class).get(PHOTO) == null) {
                    ctx.reject(new Violation(PHOTO, PlatformErrorCodes.REQUIRED, "The product has no photo to redo"));
                }
            })
            .step("Start the job", StartExternalJob.<ProcessContext>of(JOB_TYPE, ProductImage::jobInput)
                .when(ctx -> ctx.get(PRODUCT_KEY, EntityInstance.class).get(PHOTO) != null)
                .files(ctx -> List.of((UUID) ctx.get(PRODUCT_KEY, EntityInstance.class).get(PHOTO)))
                .reference(ctx -> String.valueOf(ctx.get(PRODUCT_ID)))
                .outputKey(JOB_ID)));

    static ProductImageInput jobInput(ProcessContext ctx) {
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        return new ProductImageInput(String.valueOf(product.id()), product.get("sku"), ctx.get(STYLE, String.class));
    }

    public static final ProcessDefinition<ExternalJobCompletion, ApplyOutput, ProcessContext> APPLY_PROCESS =
        ProcessDefinition.define(APPLY, 1, ExternalJobCompletion.class, ApplyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Puts the photo the AI service made on the product, and tells the requester.")
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
                .compute("Put the photo on it", (metadata, ctx) -> {
                    EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
                    ExternalJobCompletion done = ctx.get(INPUT, ExternalJobCompletion.class);
                    // Someone may have put another photo on the product while the service worked: that newer photo
                    // stays, and the redone one, referred to by nothing, goes with the file sweep.
                    boolean current = Objects.equals(product.get(PHOTO),
                        done.result(ProductImageResult.class).sourceFileId());
                    ctx.put(APPLIED, current);
                    if (!current) {
                        return;
                    }
                    Map<String, Object> changes = new LinkedHashMap<>();
                    // The platform stored the image under the field's policy: the field's write check accepts it.
                    changes.put(PHOTO, done.file(IMAGE));
                    ctx.changes().update(PRODUCT, product.id(), product.version(), changes);
                })
                .step("Tell the requester", CreateTask.when(ProductImage::hasPerson, ctx -> notice(ctx,
                    ctx.get(INPUT, ExternalJobCompletion.class).requestedBy(),
                    ctx.get(INPUT, ExternalJobCompletion.class).jobId(),
                    Boolean.TRUE.equals(ctx.get(APPLIED)) ? "task.commerce.product-image.ready"
                        : "task.commerce.product-image.stale", Map.of()))));

    public static final ProcessDefinition<ExternalJobFailure, ApplyOutput, ProcessContext> FAILED_PROCESS =
        ProcessDefinition.define(FAILED, 1, ExternalJobFailure.class, ApplyOutput.class, ProcessContext.class,
            pb -> pb
                .description("Tells the requester that the AI service could not redo the photo.")
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
                .step("Tell the requester", CreateTask.when(ProductImage::hasPerson, ctx -> notice(ctx,
                    ctx.get(INPUT, ExternalJobFailure.class).requestedBy(),
                    ctx.get(INPUT, ExternalJobFailure.class).jobId(), "task.commerce.product-image.failed",
                    Map.of("error", ctx.get(INPUT, ExternalJobFailure.class).errorCode())))));

    /** Only the platform hands results over: the process API, even for an administrator, does not. */
    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException(CALLBACK_PERMISSION,
                "Product photos are replaced from the AI service's completed jobs only");
        }
    }

    /** A job started by the system has nobody to tell. */
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
            .source("product-image:" + jobId);
    }
}
