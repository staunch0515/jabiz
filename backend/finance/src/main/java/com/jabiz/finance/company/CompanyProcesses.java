package com.jabiz.finance.company;

import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.TaxIds;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code FIN_COMPANY_PROFILE_SET}: the controller keeps the company's profile (FIN-AR-005). A change takes effect now:
 * documents issued before keep the profile they were issued with, as their archived copies.
 */
public final class CompanyProcesses {

    public static final String SET = "FIN_COMPANY_PROFILE_SET";

    /** Every field as it should be; a blank one is cleared. */
    public record ProfileInput(@NotBlank @Size(max = 200) String legalName, @Size(max = 200) String street,
        @Size(max = 100) String city, @Size(max = 20) String state, @Size(max = 20) String postalCode,
        @Size(max = 60) String country, @Size(max = 40) String phone, @Size(max = 200) String email,
        @Size(max = 1000) String remittance, @Size(max = 11) String taxId) {}

    public record ProfileOutput(String profileId, boolean changed) {}

    public static final String TAX_ID = "FIN_COMPANY_TAX_ID";

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String PROFILES = "profiles";

    public static final ProcessDefinition<ProfileInput, ProfileOutput, ProcessContext> SET_PROCESS =
        ProcessDefinition.define(SET, 1, ProfileInput.class, ProfileOutput.class, ProcessContext.class, pb -> pb
            .description("Sets the company's name, address and remittance instructions shown on its documents.")
            .permissions(FinancePermissions.COMPANY_MAINTAIN)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get(OUTPUT, ProfileOutput.class))
            .step("Load the profile", QueryEntities.of(CompanyEntities.PROFILE_DATASET, ctx -> current(), PROFILES))
            .compute("Set the profile", (metadata, ctx) -> set(ctx)));

    /** The one profile row. */
    public static EntityQuery current() {
        return EntityQuery.builder().where(new QueryPredicate.Eq("profileKey", CompanyEntities.PROFILE_KEY))
            .limit(1).build();
    }

    static void set(ProcessContext ctx) {
        ProfileInput input = ctx.get(INPUT, ProfileInput.class);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("legalName", input.legalName().trim());
        values.put("street", trim(input.street()));
        values.put("city", trim(input.city()));
        values.put("state", trim(input.state()));
        values.put("postalCode", trim(input.postalCode()));
        values.put("country", trim(input.country()));
        values.put("phone", trim(input.phone()));
        values.put("email", trim(input.email()));
        // Line breaks are kept: the document prints the instructions as written.
        values.put("remittance", input.remittance() == null || input.remittance().isBlank() ? null
            : input.remittance().strip());
        if (input.taxId() == null || input.taxId().isBlank()) {
            values.put("taxId", null);
        } else {
            java.util.Optional<String> ein = TaxIds.normalize(TaxIds.EIN, input.taxId());
            if (ein.isEmpty()) {
                ctx.reject(new Violation("taxId", TAX_ID, "The company's taxpayer identification number is an EIN, "
                    + "such as 12-3456789", Map.of("field", "taxId")));
                return;
            }
            values.put("taxId", ein.get());
        }
        @SuppressWarnings("unchecked")
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(PROFILES);
        if (found == null || found.isEmpty()) {
            values.put("profileKey", CompanyEntities.PROFILE_KEY);
            Object id = ctx.changes().insert(CompanyEntities.PROFILE, values);
            ctx.put(OUTPUT, new ProfileOutput(String.valueOf(id), true));
            return;
        }
        EntityInstance current = found.getFirst();
        Map<String, Object> changes = new LinkedHashMap<>();
        values.forEach((field, value) -> {
            if (!Objects.equals(current.get(field), value)) {
                changes.put(field, value);
            }
        });
        if (!changes.isEmpty()) {
            ctx.changes().update(CompanyEntities.PROFILE, current.id(), current.version(), changes);
        }
        ctx.put(OUTPUT, new ProfileOutput(String.valueOf(current.id()), !changes.isEmpty()));
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private CompanyProcesses() {}
}
