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
     * @param writeOffRuleChange the same for the rule that write-offs need an approver of write-offs (FIN-AR-012)
     */
    public record SetupOutput(List<String> rolesCreated, int permissionsAdded, boolean currencyCreated,
        String approvalRuleChange, String writeOffRuleChange) {}

    /** The rule of FIN-GL-015: manual entries above 10,000.00 need an approver of journal entries. */
    public static final String APPROVAL_RULE = "FIN-MANUAL-10K";
    /** The rule of FIN-AR-012: every write-off needs an approver of write-offs. */
    public static final String WRITE_OFF_RULE = "FIN-WRITE-OFF";

    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String CURRENCIES = "currencies";
    static final String OUTPUT = "output";
    static final String RULES = "rules";
    static final String PROPOSALS = "proposals";
    static final String PROPOSAL = "proposal";
    static final String PROPOSED = "proposed";
    static final String PROPOSED_CODES = "proposedCodes";

    public static final ProcessDefinition<SetupInput, SetupOutput, ProcessContext> PROCESS =
        ProcessDefinition.define(SETUP, 1, SetupInput.class, SetupOutput.class, ProcessContext.class, pb -> pb
            .description("Creates the finance roles with their permissions and the functional currency; adds what "
                + "is missing when run again.")
            .permissions(FinancePermissions.SETUP)
            .requiresMfa(MfaRequirement.ADMINISTRATION)
            .contextFactory((start, input) -> new ProcessContext(start))
            .outputMapper(SetupProcesses::output)
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
            .step("Load the approval rules", QueryEntities.of(ApprovalEntities.RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("ruleCode",
                    List.of(APPROVAL_RULE, WRITE_OFF_RULE))).limit(2).build(), RULES))
            .step("Look for their proposals", QueryEntities.of(ApprovalEntities.CONTROL_CHANGE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("status", ApprovalEntities.PROPOSED),
                        new QueryPredicate.Or(List.of(
                            new QueryPredicate.Like("changeValues", "%\"" + APPROVAL_RULE + "\"%"),
                            new QueryPredicate.Like("changeValues", "%\"" + WRITE_OFF_RULE + "\"%"))))))
                    .limit(10).build(), PROPOSALS))
            .compute("Add what is missing", (metadata, ctx) -> setup(ctx))
            // Proposed only: rules change with four eyes, so another person publishes them (FIN-CT-002).
            .step("Propose the approval rules", CallProcess.forEach(ControlChanges.PROPOSE, 1,
                ctx -> ctx.contains(PROPOSAL) ? (List<?>) ctx.get(PROPOSAL) : List.of(), PROPOSED)));

    /** The setup with the changes it proposed, by rule. */
    @SuppressWarnings("unchecked")
    private static SetupOutput output(ProcessContext ctx) {
        SetupOutput output = ctx.get(OUTPUT, SetupOutput.class);
        List<String> codes = ctx.contains(PROPOSED_CODES) ? (List<String>) ctx.get(PROPOSED_CODES) : List.of();
        List<ControlChanges.ChangeOutput> proposed = ctx.contains(PROPOSED)
            ? (List<ControlChanges.ChangeOutput>) ctx.get(PROPOSED) : List.of();
        String journal = null;
        String writeOff = null;
        for (int i = 0; i < codes.size() && i < proposed.size(); i++) {
            if (APPROVAL_RULE.equals(codes.get(i))) {
                journal = proposed.get(i).changeId();
            } else {
                writeOff = proposed.get(i).changeId();
            }
        }
        return new SetupOutput(output.rolesCreated(), output.permissionsAdded(), output.currencyCreated(), journal,
            writeOff);
    }

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
        List<ControlChanges.ProposeInput> proposals = new ArrayList<>();
        List<String> codes = new ArrayList<>();
        if (missing(ctx, APPROVAL_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, approvalRule(), null,
                "Finance setup: manual journal entries above 10,000.00 need approval (FIN-GL-015)"));
            codes.add(APPROVAL_RULE);
        }
        if (missing(ctx, WRITE_OFF_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, writeOffRule(), null,
                "Finance setup: write-offs need an approver of write-offs (FIN-AR-012)"));
            codes.add(WRITE_OFF_RULE);
        }
        ctx.put(PROPOSAL, List.copyOf(proposals));
        ctx.put(PROPOSED_CODES, List.copyOf(codes));
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added, currency, null, null));
    }

    /** Neither the rule nor a proposal of it exists. */
    private static boolean missing(ProcessContext ctx, String code) {
        return list(ctx, RULES).stream().noneMatch(r -> code.equals(r.get("ruleCode")))
            && list(ctx, PROPOSALS).stream().noneMatch(p -> String.valueOf((Object) p.get("changeValues"))
                .contains("\"" + code + "\""));
    }

    /** Every write-off, whatever its amount, needs an approver of write-offs (FIN-AR-012). */
    static Map<String, Object> writeOffRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", WRITE_OFF_RULE);
        rule.put("subject", com.jabiz.finance.ar.WriteOffProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of());
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.WRITE_OFF_APPROVE)));
        rule.put("description", "Write-offs need an approver of write-offs");
        return rule;
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
