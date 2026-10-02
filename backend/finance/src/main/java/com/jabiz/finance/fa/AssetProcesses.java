package com.jabiz.finance.fa;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.numbering.AssignNumber;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * {@code FIN_ASSET_CREATE} (internal): an asset of the register, numbered without gaps ({@code FA-003}), made by the
 * bill line that capitalizes it in the bill's own transaction (FIN-AP-007). Its permission is granted to no role.
 */
public final class AssetProcesses {

    public static final String CREATE = "FIN_ASSET_CREATE";

    public record AssetInput(@NotBlank @Size(max = 500) String description, @NotBlank String costAccount,
        @NotNull BigDecimal cost, @NotNull LocalDate inServiceDate, String department, String location,
        UUID sourceBillId, String sourceBillNo, String vendorCode, UUID transactionId) {}

    public record AssetOutput(String assetId, String assetNo) {}

    static final String INPUT = "input";
    static final String NUMBER = "number";
    static final String OUTPUT = "output";

    public static final ProcessDefinition<AssetInput, AssetOutput, ProcessContext> CREATE_PROCESS =
        ProcessDefinition.define(CREATE, 1, AssetInput.class, AssetOutput.class, ProcessContext.class, pb -> pb
            .description("Registers a fixed asset a bill capitalized.")
            .permissions(FinancePermissions.AP_INTERNAL)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, AssetOutput.class))
            .step("Number the asset", AssignNumber.of(AssetEntities.ASSET_NUMBERS, NUMBER))
            .compute("Register it", (metadata, ctx) -> {
                AssetInput input = ctx.get(INPUT, AssetInput.class);
                String number = ctx.get(NUMBER, String.class);
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("assetNo", number);
                values.put("description", input.description());
                values.put("costAccount", input.costAccount());
                values.put("cost", input.cost());
                values.put("inServiceDate", input.inServiceDate());
                values.put("department", input.department());
                values.put("location", input.location());
                values.put("sourceBillId", input.sourceBillId());
                values.put("sourceBillNo", input.sourceBillNo());
                values.put("vendorCode", input.vendorCode());
                values.put("transactionId", input.transactionId());
                values.put("active", true);
                Object id = ctx.changes().insert(AssetEntities.ASSET, values);
                ctx.put(OUTPUT, new AssetOutput(String.valueOf(id), number));
            }));

    /** Asset numbers {@code FA-001} on; the sample company's register has FA-001 and FA-002 already (F6). */
    public static NumberSequence assetNumbers(long first) {
        return NumberSequence.define(AssetEntities.ASSET_NUMBERS, s -> s.format("FA-{n:3}").startAt(first));
    }

    private AssetProcesses() {}
}
