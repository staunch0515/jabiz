package com.jabiz.finance.setup;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.MfaRequirement;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code FIN_SETUP} prepares new books (docs/finance/00-design.md section 4.6): the finance roles of
 * {@link FinanceRoles} with their permissions, and the functional currency, US dollars. It can run again at any time:
 * it adds what is missing (a later phase's permissions, a role deleted by mistake) and removes nothing, so
 * permissions an administrator granted on top stay. Granting permissions is platform administration, so it asks for a
 * second factor like the platform's own security processes.
 */
public final class SetupProcesses {

    public static final String SETUP = "FIN_SETUP";

    /** The functional currency of the company (FIN-FX-001). */
    public static final String FUNCTIONAL_CURRENCY = "USD";

    public record SetupInput() {}

    /**
     * @param rolesCreated       roles that did not exist
     * @param permissionsAdded   permissions granted to existing or new roles
     * @param currencyCreated    whether the functional currency was created
     */
    public record SetupOutput(List<String> rolesCreated, int permissionsAdded, boolean currencyCreated) {}

    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String CURRENCIES = "currencies";
    static final String OUTPUT = "output";

    public static final ProcessDefinition<SetupInput, SetupOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the finance roles with their permissions and the functional currency; adds what "
                + "is missing when run again.")
            .permissions(FinancePermissions.SETUP)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> ctx.get(OUTPUT, SetupOutput.class))
            .step("Load the roles", QueryEntities.of(SecurityEntities.ROLE_DATASET, ctx -> EntityQuery.builder()
                .where(new QueryPredicate.In("roleCode", new ArrayList<>(FinanceRoles.all().stream()
                    .map(FinanceRoles.Role::code).toList())))
                .limit(FinanceRoles.all().size() + 1)
                .build(), ROLES))
            .step("Load their permissions", QueryEntities.of(SecurityEntities.ROLE_PERMISSION_DATASET,
                ctx -> EntityQuery.builder()
                    .where(new QueryPredicate.In("roleId", new ArrayList<>(list(ctx, ROLES).stream()
                        .map(EntityInstance::id).toList())))
                    .limit(500)
                    .build(), GRANTS))
            .step("Load the functional currency", QueryEntities.of(GlEntities.CURRENCY_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("currencyCode", FUNCTIONAL_CURRENCY))
                    .limit(1).build(), CURRENCIES))
            .compute("Add what is missing", (metadata, ctx) -> setup(ctx)));

    static void setup(ProcessContext ctx) {
        Map<String, Object> roleIds = new HashMap<>();
        for (EntityInstance role : list(ctx, ROLES)) {
            roleIds.put(role.get("roleCode"), role.id());
        }
        Set<String> granted = new HashSet<>();
        for (EntityInstance grant : list(ctx, GRANTS)) {
            granted.add(grant.get("roleId") + " " + grant.get("permission"));
        }
        List<String> created = new ArrayList<>();
        int added = 0;
        for (FinanceRoles.Role role : FinanceRoles.all()) {
            Object roleId = roleIds.get(role.code());
            if (roleId == null) {
                roleId = ctx.changes().insert(SecurityEntities.ROLE, Map.of("roleCode", role.code(),
                    "labels", Map.of("en", role.label()), "enabled", true));
                created.add(role.code());
            }
            for (String permission : role.permissions()) {
                if (granted.add(roleId + " " + permission)) {
                    ctx.changes().insert(SecurityEntities.ROLE_PERMISSION,
                        Map.of("roleId", roleId, "permission", permission));
                    added++;
                }
            }
        }
        boolean currency = list(ctx, CURRENCIES).isEmpty();
        if (currency) {
            Map<String, Object> usd = new LinkedHashMap<>();
            usd.put("currencyCode", FUNCTIONAL_CURRENCY);
            usd.put("currencyName", "US dollar");
            usd.put("minorUnits", BigDecimal.valueOf(2));
            usd.put("active", true);
            ctx.changes().insert(GlEntities.CURRENCY, usd);
        }
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added, currency));
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private SetupProcesses() {}
}
