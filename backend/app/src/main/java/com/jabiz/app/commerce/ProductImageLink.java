package com.jabiz.app.commerce;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.FetchRemoteImage;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.security.MaskedLink;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.PRODUCT;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT_DATASET;

/**
 * {@code PRODUCT_IMAGE_FROM_LINK}: a product's photo taken from a link (decision D75). The server downloads the image
 * once with the platform step {@code FetchRemoteImage} - https only, public addresses only, at most the photo policy's
 * size, re-encoded like an upload - and stores it as a photo uploaded by the requester, who therefore also needs
 * {@code commerce.media.upload}. The link is not kept: the operation records show it without its query. The old photo,
 * no longer referred to, goes with the file sweep. A link that cannot be used fails with 422 and the reason's code.
 */
public final class ProductImageLink {

    public static final String PRODUCT_IMAGE_FROM_LINK = "PRODUCT_IMAGE_FROM_LINK";

    private static final String PRODUCT_ID = "productId";
    private static final String PRODUCT_KEY = "product";
    private static final String LINK = "imageLink";
    private static final String PHOTO = "photo";
    private static final String OUTPUT = "output";

    private ProductImageLink() {}

    /** @param imageLink an https link to a JPEG or PNG photo; recorded without its query */
    public record ImageLinkInput(@NotNull UUID productId, @NotBlank @Size(max = 2048) @MaskedLink String imageLink) {}

    public record ImageLinkOutput(String productId, UUID imageFileId) {}

    public static final ProcessDefinition<ImageLinkInput, ImageLinkOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(PRODUCT_IMAGE_FROM_LINK, 1, ImageLinkInput.class, ImageLinkOutput.class,
            ProcessContext.class, pb -> pb
                .description("Sets a product's photo from a link to an image, downloaded once by the server.")
                .permissions("commerce.product.write")
                .actsOn(PRODUCT, PRODUCT_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(PRODUCT_ID, input.productId());
                    ctx.put(LINK, input.imageLink());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, ImageLinkOutput.class))
                .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
                .step("Download the photo", FetchRemoteImage.<ProcessContext>of(CommerceFiles.IMAGE,
                    ctx -> ctx.get(LINK, String.class)).into(PHOTO))
                .compute("Show it", (metadata, ctx) -> {
                    EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
                    UUID fileId = ctx.get(PHOTO, FileProcesses.FileInfo.class).fileId();
                    ctx.changes().update(PRODUCT, product.id(), product.version(), Map.of("imageFileId", fileId));
                    ctx.put(OUTPUT, new ImageLinkOutput(String.valueOf(product.id()), fileId));
                }));
}
