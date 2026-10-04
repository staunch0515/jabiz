package com.jabiz.finance.ap;

import com.jabiz.approval.ContentHash;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.Violation;
import com.jabiz.finance.FinancePermissions;
import com.jabiz.finance.calc.BankNumbers;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.process.steps.QueryEntities;
import com.jabiz.security.MfaRequirement;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.jabiz.finance.ap.VendorProcesses.code;
import static com.jabiz.finance.ap.VendorProcesses.list;
import static com.jabiz.finance.ap.VendorProcesses.trim;

/**
 * Changes of vendors' bank details (FIN-AP-003, FIN-CT-001, FIN-CT-010, FIN-SC-001):
 * <ul>
 *   <li>{@code FIN_VENDOR_BANK_CHANGE}: asks for a vendor's new bank account, with a second factor. It is recorded
 *       as waiting ({@code PENDING}) and needs approval: the approval rules of {@code fin.ap.vendor-bank} say by whom,
 *       and without a rule that stops it the change is refused rather than taken unapproved. The platform's approval
 *       never lets the requester approve. One change of a vendor waits at a time; payments to the vendor are held
 *       meanwhile (the payment runs of F4c read the waiting change).</li>
 *   <li>{@code FIN_VENDOR_BANK_APPROVAL_RESULT}: run on the platform's approval events only, and believing only the
 *       platform's approval request and decisions it points to, never the event's own words. An approval makes the
 *       new account the one paid to and the one before it {@code REPLACED}; a rejection marks it {@code REJECTED}.
 *       Either way the decider is recorded on the account, so its audit trail shows the masked old and new values,
 *       who asked and who decided (FIN-CT-010).</li>
 * </ul>
 * The approval case is the vendor. The account number is never in the approval's facts or content: the content
 * binds the vendor, the routing number, the masked number and the time asked; the waiting account keeps its hash.
 */
public final class VendorBankProcesses {

    public static final String CHANGE = "FIN_VENDOR_BANK_CHANGE";
    public static final String APPROVAL_RESULT = "FIN_VENDOR_BANK_APPROVAL_RESULT";

    /** The approval subject of vendors' bank changes. */
    public static final String SUBJECT = "fin.ap.vendor-bank";

    public static final String PENDING_CHANGE = "FIN_VENDOR_BANK_PENDING";
    public static final String INVALID_ROUTING = "FIN_VENDOR_BANK_ROUTING";
    public static final String INVALID_ACCOUNT = "FIN_VENDOR_BANK_ACCOUNT";
    public static final String UNCHANGED = "FIN_VENDOR_BANK_UNCHANGED";
    public static final String NO_RULE = "FIN_VENDOR_BANK_NO_RULE";

    /**
     * @param bankAccountNumber the new account number (a distinctive name: {@code @Sensitive} masks it everywhere)
     * @param accountType       {@code CHECKING} (the default) or {@code SAVINGS}
     * @param reason            why it changes, as the vendor's request said
     */
    public record ChangeInput(@NotBlank @Size(max = 20) String vendorCode, @Size(max = 100) String bankName,
        @NotBlank @Size(max = 20) String routingNumber, @Sensitive @NotBlank @Size(max = 40) String bankAccountNumber,
        @Size(max = 10) String accountType, @NotBlank @Size(max = 500) String reason) {

        @Override
        public String toString() {
            return "ChangeInput[vendorCode=" + vendorCode + ", bankName=" + bankName + ", routingNumber="
                + routingNumber + ", bankAccountNumber=***, accountType=" + accountType + ", reason=" + reason + "]";
        }
    }

    /** @param status {@code PENDING}; the account is paid to once approved */
    public record ChangeOutput(String bankAccountId, String vendorCode, String status, String approvalRequestId) {}

    /** The platform's approval decision, as its events carry it: a pointer to the request, checked against it. */
    public record ApprovalResultInput(String subject, String entityId, String status, String contentHash,
        String requestId) {}

    public record ResultOutput(String bankAccountId, String vendorCode, String status, String note) {}

    static final String INPUT = "input";
    static final String OUTPUT = "output";
    static final String VENDORS = "vendors";
    static final String ACCOUNTS = "accounts";
    static final String CASE = "case";
    static final String APPROVAL = "approval";
    static final String VALUES = "values";
    static final String RECHECK = "recheck";
    static final String REQUESTS = "requests";
    static final String DECISIONS = "decisions";

    public static final ProcessDefinition<ChangeInput, ChangeOutput, ProcessContext> CHANGE_PROCESS =
        ProcessDefinition.define(CHANGE, 1, ChangeInput.class, ChangeOutput.class, ProcessContext.class, pb -> pb
            .description("Asks for a vendor's bank account to change; it is paid to once another person approves.")
            .permissions(FinancePermissions.VENDOR_BANK_MAINTAIN)
            .requiresMfa(MfaRequirement.ALWAYS)
            .contextFactory(VendorProcesses::withInput)
            .outputMapper(ctx -> ctx.get(OUTPUT, ChangeOutput.class))
            .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                ctx -> VendorProcesses.byCode(ctx.get(INPUT, ChangeInput.class).vendorCode()), VENDORS))
            .step("Load its bank accounts", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                ctx -> accountsOf(ctx.get(INPUT, ChangeInput.class).vendorCode()), ACCOUNTS))
            .compute("Check the change", (metadata, ctx) -> checkChange(ctx))
            .step("Ask for approval", RequireApproval.when(ctx -> ctx.contains(CASE), SUBJECT,
                ctx -> ctx.get(CASE, ApprovalCase.class), APPROVAL))
            // Read again under the approval's lock on the vendor: a change asked at the same time is seen now, and
            // refusing rolls back the request it would have superseded.
            .step("Look again for a waiting change", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                ctx -> accountsOf(ctx.get(INPUT, ChangeInput.class).vendorCode()), RECHECK))
            .compute("Record the change", (metadata, ctx) -> recordChange(ctx)));

    public static final ProcessDefinition<ApprovalResultInput, ResultOutput, ProcessContext> RESULT_PROCESS =
        ProcessDefinition.define(APPROVAL_RESULT, 1, ApprovalResultInput.class, ResultOutput.class,
            ProcessContext.class, pb -> pb
                .description("Takes a vendor's approved bank account into use, or marks a rejected one.")
                .permissions(FinancePermissions.VENDOR_BANK_RESULT)
                .internal()
                .contextFactory(VendorProcesses::withInput)
                .outputMapper(ctx -> ctx.get(OUTPUT, ResultOutput.class))
                .step("Load the waiting account", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET, ctx -> {
                    ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
                    // Approvals of other subjects are none of this process's business; the request names its own.
                    List<Object> requests = SUBJECT.equals(input.subject()) && input.requestId() != null
                        ? List.of(input.requestId()) : List.of();
                    return EntityQuery.builder().where(new QueryPredicate.And(List.of(
                        new QueryPredicate.In("approvalRequestId", new ArrayList<>(requests)),
                        new QueryPredicate.Eq("status", ApEntities.PENDING)))).limit(1).build();
                }, "waiting"))
                .step("Load the vendor's accounts", QueryEntities.of(ApEntities.VENDOR_BANK_DATASET,
                    ctx -> accountsOf(waiting(ctx) == null ? null : waiting(ctx).get("vendorCode")), ACCOUNTS))
                .step("Load the vendor", QueryEntities.of(ApEntities.VENDOR_DATASET,
                    ctx -> VendorProcesses.byCode(waiting(ctx) == null ? null : waiting(ctx).get("vendorCode")),
                    VENDORS))
                // The input is only a pointer: what was decided, and by whom, is read from the platform's own
                // records, so no caller can make a change take effect by saying it was approved.
                .step("Load the approval request", QueryEntities.of(ApprovalEntities.REQUEST_DATASET,
                    ctx -> byRequest("requestId", ctx), REQUESTS))
                .step("Load its decisions", QueryEntities.of(ApprovalEntities.DECISION_DATASET,
                    ctx -> byRequest("requestId", ctx), DECISIONS))
                .compute("Apply the decision", (metadata, ctx) -> applyDecision(ctx)));

    static void checkChange(ProcessContext ctx) {
        ChangeInput input = ctx.get(INPUT, ChangeInput.class);
        String vendorCode = code(input.vendorCode());
        if (list(ctx, VENDORS).isEmpty()) {
            ctx.reject(new Violation("vendorCode", VendorProcesses.UNKNOWN_VENDOR, "There is no vendor " + vendorCode,
                Map.of("vendorCode", vendorCode)));
            return;
        }
        if (list(ctx, ACCOUNTS).stream().anyMatch(a -> ApEntities.PENDING.equals(a.get("status")))) {
            ctx.reject(new Violation("vendorCode", PENDING_CHANGE, "A change of " + vendorCode + "'s bank details "
                + "waits for approval already", Map.of("vendorCode", vendorCode)));
            return;
        }
        String routing = BankNumbers.compact(input.routingNumber().trim());
        String account = BankNumbers.compact(input.bankAccountNumber().trim());
        if (!BankNumbers.validRouting(routing)) {
            ctx.reject(new Violation("routingNumber", INVALID_ROUTING, "A routing number is nine digits with a valid "
                + "check digit", Map.of()));
        }
        if (MaskStyle.looksMasked(input.bankAccountNumber().trim()) || !BankNumbers.validAccount(account)) {
            // The value is never repeated: it is the account number itself.
            ctx.reject(new Violation("bankAccountNumber", INVALID_ACCOUNT, "An account number is 4 to 17 digits, "
                + "entered whole", Map.of()));
        }
        String type = input.accountType() == null || input.accountType().isBlank() ? "CHECKING"
            : code(input.accountType());
        if (!ApEntities.ACCOUNT_TYPE_VALUES.contains(type)) {
            ctx.reject(new Violation("accountType", VendorProcesses.INVALID_VALUE, "accountType must be one of "
                + ApEntities.ACCOUNT_TYPE_VALUES, Map.of("field", "accountType", "value", input.accountType())));
        }
        if (ctx.hasViolations()) {
            return;
        }
        EntityInstance active = active(list(ctx, ACCOUNTS));
        if (active != null && routing.equals(active.get("routingNumber"))
            && account.equals(active.get("accountNumber"))) {
            ctx.reject(new Violation("bankAccountNumber", UNCHANGED, vendorCode + " is paid to this account already",
                Map.of("vendorCode", vendorCode)));
            return;
        }
        Instant now = ctx.opTime();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("vendorCode", vendorCode);
        values.put("bankName", trim(input.bankName()));
        values.put("routingNumber", routing);
        values.put("accountNumber", account);
        values.put("accountType", type);
        values.put("status", ApEntities.PENDING);
        values.put("reason", input.reason().trim());
        values.put("requestedBy", ctx.request().actorId());
        values.put("requestedTime", now);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("vendorBankChange", true);
        content.put("vendorCode", vendorCode);
        content.put("routingNumber", routing);
        content.put("accountNumber", MaskStyle.LAST4.apply(account));
        content.put("accountType", type);
        // Each request is its own: an approval of an earlier, identical one does not carry over.
        content.put("requestedAt", now);
        values.put("contentHash", ContentHash.of(content));
        ctx.put(VALUES, values);
        // The case is the vendor: its audit trail lists the approvals of its bank changes.
        ctx.put(CASE, ApprovalCase.of(list(ctx, VENDORS).getFirst().id(), Map.of("vendorCode", vendorCode),
            content).reference(vendorCode));
    }

    @SuppressWarnings("unchecked")
    static void recordChange(ProcessContext ctx) {
        if (!ctx.contains(APPROVAL)) {
            return;
        }
        if (list(ctx, RECHECK).stream().anyMatch(a -> ApEntities.PENDING.equals(a.get("status")))) {
            String vendorCode = code(ctx.get(INPUT, ChangeInput.class).vendorCode());
            ctx.reject(new Violation("vendorCode", PENDING_CHANGE, "A change of " + vendorCode + "'s bank details "
                + "waits for approval already", Map.of("vendorCode", vendorCode)));
            return;
        }
        ApprovalOutcome approval = ctx.get(APPROVAL, ApprovalOutcome.class);
        if (approval.status() != ApprovalOutcome.Status.PENDING) {
            // A bank change never takes effect unapproved: a rule must name its approvers (FIN-AP-003).
            ctx.reject(new Violation("vendorCode", NO_RULE, "No approval rule of vendor bank changes applies: a "
                + "controller sets one before bank details change", Map.of()));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) ctx.get(VALUES));
        values.put("approvalRequestId", approval.requestId());
        Object id = ctx.changes().insert(ApEntities.VENDOR_BANK, values);
        ChangeInput input = ctx.get(INPUT, ChangeInput.class);
        ctx.put(OUTPUT, new ChangeOutput(String.valueOf(id), code(input.vendorCode()), ApEntities.PENDING,
            approval.requestId()));
    }

    static void applyDecision(ProcessContext ctx) {
        ApprovalResultInput input = ctx.get(INPUT, ApprovalResultInput.class);
        EntityInstance waiting = waiting(ctx);
        if (waiting == null) {
            ctx.put(OUTPUT, new ResultOutput(null, null, input.status(), "not a bank change waiting for approval"));
            return;
        }
        String vendorCode = waiting.get("vendorCode");
        if (!Objects.equals(input.contentHash(), waiting.get("contentHash"))) {
            ctx.put(OUTPUT, new ResultOutput(String.valueOf(waiting.id()), vendorCode, ApEntities.PENDING,
                "the decision was for another request"));
            return;
        }
        EntityInstance request = list(ctx, REQUESTS).isEmpty() ? null : list(ctx, REQUESTS).getFirst();
        EntityInstance vendor = list(ctx, VENDORS).isEmpty() ? null : list(ctx, VENDORS).getFirst();
        String decided = request == null ? null : request.get("status");
        if (request == null || vendor == null || !SUBJECT.equals(request.get("subject"))
            || !String.valueOf(vendor.id()).equals(request.get("entityId"))
            || !Objects.equals(waiting.get("contentHash"), request.get("contentHash"))
            || !(ApprovalEntities.APPROVED.equals(decided) || ApprovalEntities.REJECTED.equals(decided))
            || !decided.equals(input.status())) {
            ctx.put(OUTPUT, new ResultOutput(String.valueOf(waiting.id()), vendorCode, ApEntities.PENDING,
                "the approval request does not say so"));
            return;
        }
        // The last decision closed the request: its approver, or who rejected it.
        String decidedBy = list(ctx, DECISIONS).stream()
            .max(java.util.Comparator.comparing(d -> new java.math.BigDecimal(String.valueOf((Object) d.get("levelNo")))))
            .map(d -> String.valueOf((Object) d.get("approverId"))).orElse(null);
        if (decidedBy == null) {
            ctx.put(OUTPUT, new ResultOutput(String.valueOf(waiting.id()), vendorCode, ApEntities.PENDING,
                "the approval request has no decision"));
            return;
        }
        if (!ApprovalEntities.APPROVED.equals(decided)) {
            ctx.changes().update(ApEntities.VENDOR_BANK, waiting.id(), waiting.version(),
                Map.of("status", ApEntities.REJECTED, "decidedBy", decidedBy));
            ctx.put(OUTPUT, new ResultOutput(String.valueOf(waiting.id()), vendorCode, ApEntities.REJECTED, null));
            return;
        }
        for (EntityInstance account : list(ctx, ACCOUNTS)) {
            if (ApEntities.ACTIVE.equals(account.get("status"))) {
                ctx.changes().update(ApEntities.VENDOR_BANK, account.id(), account.version(),
                    Map.of("status", ApEntities.REPLACED));
            }
        }
        ctx.changes().update(ApEntities.VENDOR_BANK, waiting.id(), waiting.version(),
            Map.of("status", ApEntities.ACTIVE, "decidedBy", decidedBy));
        ctx.put(OUTPUT, new ResultOutput(String.valueOf(waiting.id()), vendorCode, ApEntities.ACTIVE, null));
    }

    /** The account a vendor is paid to, if any. */
    public static EntityInstance active(List<EntityInstance> accounts) {
        return accounts.stream().filter(a -> ApEntities.ACTIVE.equals(a.get("status"))).findFirst().orElse(null);
    }

    /** A vendor's bank accounts, waiting, active and past. */
    public static EntityQuery accountsOf(String vendorCode) {
        String code = code(vendorCode);
        if (code == null) {
            return EntityQuery.builder().where(new QueryPredicate.In("vendorCode", List.of())).limit(1).build();
        }
        return EntityQuery.builder().where(new QueryPredicate.Eq("vendorCode", code)).limit(200).build();
    }

    private static EntityQuery byRequest(String field, ProcessContext ctx) {
        String requestId = ctx.get(INPUT, ApprovalResultInput.class).requestId();
        List<Object> ids = new ArrayList<>();
        try {
            if (requestId != null) {
                ids.add(java.util.UUID.fromString(requestId));
            }
        } catch (IllegalArgumentException e) {
            // Not a request id: nothing to find.
        }
        return EntityQuery.builder().where(new QueryPredicate.In(field, ids)).limit(20).build();
    }

    private static EntityInstance waiting(ProcessContext ctx) {
        return list(ctx, "waiting").isEmpty() ? null : list(ctx, "waiting").getFirst();
    }

    private VendorBankProcesses() {}
}
