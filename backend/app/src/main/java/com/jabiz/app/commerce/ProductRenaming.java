package com.jabiz.app.commerce;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.LoadEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.PRODUCT;
import static com.jabiz.app.commerce.CommerceEntities.PRODUCT_DATASET;

/**
 * {@code PRODUCT_RENAME}: a product's new name is checked by the demo moderation service ({@link ModerateText}, a
 * short external call of a blocking step, decision D38) before it is saved. A flagged name is refused with 422; a
 * service that does not answer usably fails the process with 502, and nothing changes either way.
 *
 * <p>Only this process moderates: the demo shows an external call, not a moderated field. Product names written
 * through the generic form or the dataset API (creation included) are not checked; an application that needs every
 * name moderated makes the field {@code processOnly()} and creates products through a moderated process too.
 */
public final class ProductRenaming {

    public static final String PRODUCT_RENAME = "PRODUCT_RENAME";

    private static final String PRODUCT_ID = "productId";
    private static final String PRODUCT_KEY = "product";
    private static final String NEW_NAME = "newName";
    private static final String OUTPUT = "output";

    private ProductRenaming() {}

    public record RenameInput(@NotNull UUID productId, @NotBlank @Size(max = 200) String productName) {}

    public record RenameOutput(String productId, String sku, String productName) {}

    public static final ProcessDefinition<RenameInput, RenameOutput, ProcessContext> RENAME_PROCESS =
        ProcessDefinition.define(PRODUCT_RENAME, 1, RenameInput.class, RenameOutput.class, ProcessContext.class,
            pb -> pb
                .description("Renames a product once the moderation service has accepted the new name.")
                .permissions("commerce.product.write")
                .actsOn(PRODUCT, PRODUCT_ID)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(PRODUCT_ID, input.productId());
                    ctx.put(NEW_NAME, input.productName().strip());
                    return ctx;
                })
                .outputMapper(ctx -> ctx.get(OUTPUT, RenameOutput.class))
                .step("Load the product", LoadEntity.by(PRODUCT_DATASET, PRODUCT_ID, PRODUCT_KEY))
                .step("Moderate the new name", ModerateText.of(NEW_NAME, "productName"))
                .compute("Rename", (metadata, ctx) -> rename(ctx)));

    static void rename(ProcessContext ctx) {
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance product = ctx.get(PRODUCT_KEY, EntityInstance.class);
        String name = ctx.get(NEW_NAME, String.class);
        if (!name.equals(product.get("productName"))) {
            ctx.changes().update(PRODUCT, product.id(), product.version(), Map.of("productName", name));
        }
        ctx.put(OUTPUT, new RenameOutput(String.valueOf(product.id()), product.get("sku"), name));
    }
}
