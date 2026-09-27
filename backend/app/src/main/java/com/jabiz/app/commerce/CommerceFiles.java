package com.jabiz.app.commerce;

import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.file.FileProcesses;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.process.steps.SaveChanges;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Files of the commerce sample (docs/design/14-files.md): product photos ({@value #IMAGE}) and supplier contracts
 * ({@value #DOCUMENT}), and {@code SUPPLIER_CONTRACT_REMOVE}, which shows how a business process deletes a file it
 * no longer needs: clear the reference, save, then call {@code FILE_DELETE} in the same transaction.
 */
@Configuration
public class CommerceFiles {

    public static final String IMAGE = "commerce.image";
    public static final String DOCUMENT = "commerce.document";

    public static final String CONTRACT_REMOVE = "SUPPLIER_CONTRACT_REMOVE";

    public record ContractRemoveInput(@NotBlank String supplierId) {}

    /** @param fileId the deleted file; null when the supplier had no contract */
    public record ContractRemoveOutput(String supplierId, UUID fileId) {}

    private static final String SUPPLIER_ID = "supplierId";
    private static final String SUPPLIER = "supplier";
    private static final String FILE_ID = "fileId";

    public static final ProcessDefinition<ContractRemoveInput, ContractRemoveOutput, ProcessContext>
        CONTRACT_REMOVE_PROCESS = ProcessDefinition.define(CONTRACT_REMOVE, 1, ContractRemoveInput.class, ContractRemoveOutput.class,
            ProcessContext.class, pb -> pb
                .description("Removes a supplier's contract and deletes its file.")
                .permissions("commerce.supplier.write")
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(SUPPLIER_ID, input.supplierId());
                    return ctx;
                })
                .outputMapper(ctx -> new ContractRemoveOutput(ctx.get(SUPPLIER_ID, String.class),
                    ctx.contains(FILE_ID) ? ctx.get(FILE_ID, UUID.class) : null))
                .step("Load the supplier", LoadEntity.by(SupplierDefinitions.DATASET, SUPPLIER_ID, SUPPLIER))
                .compute("Clear the contract", (metadata, ctx) -> {
                    EntityInstance supplier = ctx.get(SUPPLIER, EntityInstance.class);
                    Object fileId = supplier.get("contractFileId");
                    if (fileId == null) {
                        return;
                    }
                    ctx.put(FILE_ID, fileId);
                    Map<String, Object> cleared = new HashMap<>();
                    cleared.put("contractFileId", null);
                    ctx.changes().update(SupplierDefinitions.SUPPLIER, supplier.id(), supplier.version(), cleared);
                })
                // FILE_DELETE checks references in the database: the cleared field must be saved first.
                .step("Save", SaveChanges.now())
                .step("Delete the file", CallProcess.<ProcessContext>when(ctx -> ctx.contains(FILE_ID),
                    FileProcesses.DELETE, 1, ctx -> new FileProcesses.DeleteInput(ctx.get(FILE_ID, UUID.class)),
                    null)));

    @Bean
    FilePolicy commerceImagePolicy() {
        return FilePolicy.define(IMAGE)
            .allow(MediaTypes.JPEG, MediaTypes.PNG)
            .maxBytes(10 * FilePolicy.MB)
            .image(i -> i.maxPixels(40_000_000).variants(160, 640, 1280))
            .permissions("commerce.media.upload", "commerce.media.read")
            .build();
    }

    @Bean
    FilePolicy commerceDocumentPolicy() {
        return FilePolicy.define(DOCUMENT)
            .allow(MediaTypes.PDF)
            .maxBytes(20 * FilePolicy.MB)
            .permissions("commerce.document.upload", "commerce.document.read")
            .build();
    }

    @Bean
    ProcessDefinition<ContractRemoveInput, ContractRemoveOutput, ProcessContext> supplierContractRemoveProcess() {
        return CONTRACT_REMOVE_PROCESS;
    }
}
