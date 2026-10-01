package com.jabiz.finance.setup;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.gl.GlEntities;
import com.jabiz.finance.gl.JournalProcesses;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ControlChanges;
import com.jabiz.runtime.process.steps.CallProcess;
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
     * @param approvalRuleChange the proposed change creating the journal approval rule, which another person
     *                           publishes (four eyes, FIN-CT-002); null when the rule exists or is proposed already
     */
    public record SetupOutput(List<String> rolesCreated, int permissionsAdded, boolean currencyCreated,
        String approvalRuleChange) {}

    /** The rule of FIN-GL-015: manual entries above 10,000.00 need an approver of journal entries. */
    public static final String APPROVAL_RULE = "FIN-MANUAL-10K";

    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String CURRENCIES = "currencies";
    static final String OUTPUT = "output";
    static final String RULES = "rules";
    static final String PROPOSALS = "proposals";
    static final String PROPOSAL = "proposal";
    static final String PROPOSED = "proposed";

    public static final ProcessDefinition<SetupInput, SetupOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the finance roles with their permissions and the functional currency; adds what "
                + "is missing when run again.")
            .permissions(FinancePermissions.SETUP)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(ctx -> {
                SetupOutput output = ctx.get(OUTPUT, SetupOutput.class);
                return ctx.contains(PROPOSED) ? new SetupOutput(output.rolesCreated(), output.permissionsAdded(),
                    output.currencyCreated(), ctx.get(PROPOSED, ControlChanges.ChangeOutput.class).changeId())
                    : output;
            })
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
            .step("Load the journal approval rule", QueryEntities.of(ApprovalEntities.RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.Eq("ruleCode", APPROVAL_RULE)).limit(1).build(),
                RULES))
            .step("Look for its proposal", QueryEntities.of(ApprovalEntities.CONTROL_CHANGE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("status", ApprovalEntities.PROPOSED),
                        new QueryPredicate.Like("changeValues", "%\"" + APPROVAL_RULE + "\"%"))))
                    .limit(1).build(), PROPOSALS))
            .compute("Add what is missing", (metadata, ctx) -> setup(ctx))
            // Proposed only: rules change with four eyes, so another person publishes it (FIN-CT-002).
            .step("Propose the approval rule", CallProcess.when(ctx -> ctx.contains(PROPOSAL), ControlChanges.PROPOSE,
                1, ctx -> ctx.get(PROPOSAL), PROPOSED)));

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
        if (list(ctx, RULES).isEmpty() && list(ctx, PROPOSALS).isEmpty()) {
            ctx.put(PROPOSAL, new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, approvalRule(), null,
                "Finance setup: manual journal entries above 10,000.00 need approval (FIN-GL-015)"));
        }
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added, currency, null));
    }

    /** The rule's values as a control change proposes them. */
    static Map<String, Object> approvalRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", APPROVAL_RULE);
        rule.put("subject", JournalProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of("all", List.of(
            Map.of("fact", "manual", "op", "eq", "value", true),
            Map.of("fact", "amount", "op", "gt", "value", 10000))));
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.JOURNAL_APPROVE)));
        rule.put("description", "Manual journal entries above 10,000.00 need an approver of journal entries");
        return rule;
    }

    @SuppressWarnings("unchecked")
    private static List<EntityInstance> list(ProcessContext ctx, String key) {
        List<EntityInstance> found = (List<EntityInstance>) ctx.get(key);
        return found == null ? List.of() : found;
    }

    private SetupProcesses() {}
}
