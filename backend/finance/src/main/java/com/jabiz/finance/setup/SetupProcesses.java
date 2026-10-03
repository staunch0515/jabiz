package com.jabiz.finance.setup;

import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.close.CloseEntities;
import com.jabiz.finance.close.CloseProcesses;
import com.jabiz.finance.report.StatementEntities;
import com.jabiz.finance.report.StatementProcesses;
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
     * @param proposedChanges    every change proposed by this run, by the code of its rule: the approval rules above,
     *                           the rule of vendor bank changes and the segregation-of-duties rules of payables
     * @param closeItemsCreated  the items of the sample close checklist made, when there was no checklist (F8a)
     * @param layoutsCreated     the sample statement layouts made, each when there was none of its code (F9b)
     */
    public record SetupOutput(List<String> rolesCreated, int permissionsAdded, boolean currencyCreated,
        String approvalRuleChange, String writeOffRuleChange, Map<String, String> proposedChanges,
        int closeItemsCreated, int layoutsCreated) {}

    /** The rule of FIN-GL-015: manual entries above 10,000.00 need an approver of journal entries. */
    public static final String APPROVAL_RULE = "FIN-MANUAL-10K";
    /** The rule of FIN-AR-012: every write-off needs an approver of write-offs. */
    public static final String WRITE_OFF_RULE = "FIN-WRITE-OFF";
    /** The rule of FIN-AP-003: every change of a vendor's bank details needs another person's approval. */
    public static final String VENDOR_BANK_RULE = "FIN-VENDOR-BANK";
    /** Segregation of duties (FIN-CT-001): who keeps vendors' bank details never releases payments. */
    public static final String SOD_VENDOR_BANK = "FIN-SOD-VENDOR-BANK-RELEASE";
    /** Segregation of duties (FIN-CT-001 acceptance 2): who prepares payables never releases payments. */
    public static final String SOD_PAYABLES = "FIN-SOD-PAYABLES-RELEASE";
    /**
     * FIN-AP-011: the platform keeps the last to change a run from approving it; this keeps anyone who prepares
     * runs from approving them, so no preparer approves lines another preparer submitted.
     */
    public static final String SOD_PAYMENT_APPROVE = "FIN-SOD-PAYMENT-APPROVE";
    /** Segregation of duties (FIN-CT-001): who keeps users and roles never prepares or posts the books' documents. */
    public static final String SOD_ADMIN_POST = "FIN-SOD-ADMIN-POST";
    /** The permissions that keep users and roles, and those that make the books' documents (FIN-CT-001). */
    static final List<String> ADMIN_PERMISSIONS = List.of("security.user.create", "security.user.write",
        "security.role.write", "security.user-role.write");
    static final List<String> POSTING_PERMISSIONS = List.of(FinancePermissions.JOURNAL_PREPARE,
        FinancePermissions.INVOICE_PREPARE, FinancePermissions.RECEIPT_RECORD, FinancePermissions.BILL_PREPARE,
        FinancePermissions.PAYMENT_PREPARE);

    /** The rule of FIN-AP-006: bills above 10,000.00 need a controller's approval before they are paid. */
    public static final String BILL_RULE = "FIN-AP-BILL-10K";

    /** The rule of FIN-AP-011: every payment run needs an approver of payments other than its preparer. */
    public static final String PAYMENT_RULE = "FIN-AP-PAYMENT";

    /** The rule of FIN-BK-008: every bank reconciliation needs a reviewer of reconciliations to sign it off. */
    public static final String BANK_REC_RULE = "FIN-BANK-REC";

    /** The rule of FIN-PC-006: every reopening of a closed period needs a controller other than its requester. */
    public static final String REOPEN_RULE = "FIN-PERIOD-REOPEN";

    private static final List<String> APPROVAL_RULES = List.of(APPROVAL_RULE, WRITE_OFF_RULE, VENDOR_BANK_RULE,
        BILL_RULE, PAYMENT_RULE, BANK_REC_RULE, REOPEN_RULE);
    private static final List<String> SOD_RULES = List.of(SOD_VENDOR_BANK, SOD_PAYABLES, SOD_PAYMENT_APPROVE,
        SOD_ADMIN_POST);

    static final String ROLES = "roles";
    static final String GRANTS = "grants";
    static final String CURRENCIES = "currencies";
    static final String OUTPUT = "output";
    static final String RULES = "rules";
    static final String SOD = "sodRules";
    static final String PROPOSALS = "proposals";
    static final String PROPOSAL = "proposal";
    static final String PROPOSED = "proposed";
    static final String PROPOSED_CODES = "proposedCodes";
    static final String CLOSE_ITEMS = "closeItems";
    static final String LAYOUTS = "layouts";

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
                    new ArrayList<>(APPROVAL_RULES))).limit(APPROVAL_RULES.size()).build(), RULES))
            .step("Load the segregation rules", QueryEntities.of(ApprovalEntities.SOD_RULE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("ruleCode",
                    new ArrayList<>(SOD_RULES))).limit(SOD_RULES.size()).build(), SOD))
            .step("Look for their proposals", QueryEntities.of(ApprovalEntities.CONTROL_CHANGE_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.Eq("status", ApprovalEntities.PROPOSED),
                        new QueryPredicate.Or(java.util.stream.Stream.concat(APPROVAL_RULES.stream(),
                                SOD_RULES.stream())
                            .map(code -> (QueryPredicate) new QueryPredicate.Like("changeValues",
                                "%\"" + code + "\"%")).toList()))))
                    .limit(20).build(), PROPOSALS))
            .step("Load the close checklist", QueryEntities.of(CloseEntities.TEMPLATE_DATASET,
                ctx -> EntityQuery.builder().limit(1).build(), CLOSE_ITEMS))
            .step("Load the statement layouts", QueryEntities.of(StatementEntities.LAYOUT_DATASET,
                ctx -> EntityQuery.builder().where(new QueryPredicate.In("layoutCode", new ArrayList<>(
                    StatementProcesses.SAMPLES.stream().map(StatementProcesses.Sample::code).toList())))
                    .limit(1000).build(), LAYOUTS))
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
        Map<String, String> changes = new LinkedHashMap<>();
        for (int i = 0; i < codes.size() && i < proposed.size(); i++) {
            changes.put(codes.get(i), proposed.get(i).changeId());
        }
        return new SetupOutput(output.rolesCreated(), output.permissionsAdded(), output.currencyCreated(),
            changes.get(APPROVAL_RULE), changes.get(WRITE_OFF_RULE), Map.copyOf(changes), output.closeItemsCreated(),
            output.layoutsCreated());
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
        if (missing(ctx, VENDOR_BANK_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, vendorBankRule(), null,
                "Finance setup: vendor bank changes need another person's approval (FIN-AP-003)"));
            codes.add(VENDOR_BANK_RULE);
        }
        if (missing(ctx, BILL_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, billRule(), null,
                "Finance setup: bills above 10,000.00 need a controller's approval before payment (FIN-AP-006)"));
            codes.add(BILL_RULE);
        }
        if (missing(ctx, PAYMENT_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, paymentRule(), null,
                "Finance setup: payment runs need an approver of payments (FIN-AP-011)"));
            codes.add(PAYMENT_RULE);
        }
        if (missing(ctx, BANK_REC_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, bankRecRule(), null,
                "Finance setup: bank reconciliations need a reviewer's sign-off (FIN-BK-008)"));
            codes.add(BANK_REC_RULE);
        }
        if (missing(ctx, REOPEN_RULE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.RULE, null, null, reopenRule(), null,
                "Finance setup: reopening a closed period needs another controller's approval (FIN-PC-006)"));
            codes.add(REOPEN_RULE);
        }
        if (missing(ctx, SOD_VENDOR_BANK)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.SOD_RULE, null, null,
                sodRule(SOD_VENDOR_BANK, List.of(FinancePermissions.VENDOR_BANK_MAINTAIN),
                    "Who keeps vendors' bank details never releases payments"), null,
                "Finance setup: keeping vendor bank details and releasing payments are apart (FIN-CT-001)"));
            codes.add(SOD_VENDOR_BANK);
        }
        if (missing(ctx, SOD_PAYABLES)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.SOD_RULE, null, null,
                sodRule(SOD_PAYABLES, List.of(FinancePermissions.BILL_PREPARE, FinancePermissions.PAYMENT_PREPARE),
                    "Who prepares bills or payment runs never releases payments"), null,
                "Finance setup: preparing payables and releasing payments are apart (FIN-CT-001)"));
            codes.add(SOD_PAYABLES);
        }
        if (missing(ctx, SOD_PAYMENT_APPROVE)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.SOD_RULE, null, null,
                sodRule(SOD_PAYMENT_APPROVE, List.of(FinancePermissions.PAYMENT_PREPARE),
                    FinancePermissions.PAYMENT_APPROVE, "Who prepares payment runs never approves them"), null,
                "Finance setup: preparing and approving payment runs are apart (FIN-AP-011, FIN-CT-001)"));
            codes.add(SOD_PAYMENT_APPROVE);
        }
        if (missing(ctx, SOD_ADMIN_POST)) {
            proposals.add(new ControlChanges.ProposeInput(ApprovalEntities.SOD_RULE, null, null,
                sodRule(SOD_ADMIN_POST, ADMIN_PERMISSIONS, String.join(",", POSTING_PERMISSIONS),
                    "Who keeps users and roles never prepares or posts the books' documents"), null,
                "Finance setup: keeping users and roles and posting transactions are apart (FIN-CT-001)"));
            codes.add(SOD_ADMIN_POST);
        }
        ctx.put(PROPOSAL, List.copyOf(proposals));
        ctx.put(PROPOSED_CODES, List.copyOf(codes));
        // The sample close checklist (FIN-PC-004) when there is none; once there is, it is the controller's.
        int closeItems = 0;
        if (list(ctx, CLOSE_ITEMS).isEmpty()) {
            for (CloseProcesses.TemplateInput item : CloseProcesses.SAMPLE) {
                ctx.changes().insert(CloseEntities.TEMPLATE, CloseProcesses.templateRow(item));
                closeItems++;
            }
        }
        // The sample statement layouts (FIN-RP-002, RP-011), each when there is none of its code.
        int layouts = 0;
        for (StatementProcesses.Sample sample : StatementProcesses.SAMPLES) {
            if (list(ctx, LAYOUTS).stream().noneMatch(l -> sample.code().equals(l.get("layoutCode")))) {
                StatementProcesses.write(ctx, sample.code(), sample.statement(), sample.title(), 1, sample.rows());
                layouts++;
            }
        }
        ctx.put(OUTPUT, new SetupOutput(List.copyOf(created), added, currency, null, null, Map.of(), closeItems,
            layouts));
    }

    /** Neither the rule nor a proposal of it exists. */
    private static boolean missing(ProcessContext ctx, String code) {
        return list(ctx, RULES).stream().noneMatch(r -> code.equals(r.get("ruleCode")))
            && list(ctx, SOD).stream().noneMatch(r -> code.equals(r.get("ruleCode")))
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

    /** Every change of a vendor's bank details needs an approver of them (FIN-AP-003). */
    static Map<String, Object> vendorBankRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", VENDOR_BANK_RULE);
        rule.put("subject", com.jabiz.finance.ap.VendorBankProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of());
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.VENDOR_BANK_APPROVE)));
        rule.put("description", "Vendor bank changes need an approver of them");
        return rule;
    }

    /** Bills above 10,000.00 need an approver of bills (FIN-AP-006). */
    static Map<String, Object> billRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", BILL_RULE);
        rule.put("subject", com.jabiz.finance.ap.BillProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of("all", List.of(Map.of("fact", "amount", "op", "gt", "value", 10000))));
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.BILL_APPROVE)));
        rule.put("description", "Bills above 10,000.00 need an approver of bills");
        return rule;
    }

    /** Every payment run needs an approver of payments (FIN-AP-011, 015). */
    static Map<String, Object> paymentRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", PAYMENT_RULE);
        rule.put("subject", com.jabiz.finance.ap.PaymentProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of());
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.PAYMENT_APPROVE)));
        rule.put("description", "Payment runs need an approver of payments");
        return rule;
    }

    /** Every bank reconciliation needs a reviewer of reconciliations other than its preparer (FIN-BK-008). */
    static Map<String, Object> bankRecRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", BANK_REC_RULE);
        rule.put("subject", com.jabiz.finance.bank.ReconciliationProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of());
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.BANK_REC_REVIEW)));
        rule.put("description", "Bank reconciliations need a reviewer of reconciliations");
        return rule;
    }

    /** Every reopening of a closed period needs a controller other than its requester (FIN-PC-006). */
    static Map<String, Object> reopenRule() {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", REOPEN_RULE);
        rule.put("subject", com.jabiz.finance.close.ReopenProcesses.SUBJECT);
        rule.put("priority", 100);
        rule.put("enabled", true);
        rule.put("condition", Map.of());
        rule.put("levels", List.of(Map.of("permission", FinancePermissions.PERIOD_CLOSE)));
        rule.put("description", "Reopening a closed period needs a controller's approval");
        return rule;
    }

    /** A segregation-of-duties rule: no one holds a permission of each group (platform 18 section 4.1). */
    static Map<String, Object> sodRule(String code, List<String> left, String description) {
        return sodRule(code, left, FinancePermissions.PAYMENT_RELEASE, description);
    }

    static Map<String, Object> sodRule(String code, List<String> left, String right, String description) {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("ruleCode", code);
        rule.put("leftPermissions", String.join(",", left));
        rule.put("rightPermissions", right);
        rule.put("enabled", true);
        rule.put("description", description);
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
