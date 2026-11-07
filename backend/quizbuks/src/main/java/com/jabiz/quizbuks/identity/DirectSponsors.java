package com.jabiz.quizbuks.identity;

import com.jabiz.process.ProcessContext;
import com.jabiz.quizbuks.operator.QbOperators;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * An operator's own sponsor (F-S1, docs/quizbuks/plans/impl/F-S1.md §0): its first lead's account, made a sponsor in
 * the transaction that names that lead. It writes what a reviewed onboarding leaves (an approved onboarding) and the
 * fixed assignment to the operator; the sponsor role is not granted here: the lead joins the sponsor entry themself
 * (the platform's join, {@code SponsorApplyGuard} lets them), as every other way of taking a role is the platform's.
 * Its publications are reviewed by another member of the operator, as the platform's approvals never let their
 * preparer decide.
 */
public final class DirectSponsors {

    private DirectSponsors() {}

    /**
     * Writes the approved onboarding and the fixed assignment of {@code userId} to {@code operatorId}. The caller holds
     * the operator's lock, the person's membership, account and assignment locks, and has checked that the person has
     * no onboarding and no assignment.
     *
     * @param contactName the lead's name; the sponsor changes it, and the rest of the profile, in the sponsor console
     * @param company     the operator's name, which the direct sponsor publishes under until they change it
     */
    public static void write(ProcessContext ctx, String operatorId, String userId, String contactName,
        String company) {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("userId", userId);
        pending.put("contactName", contactName == null || contactName.isBlank() ? company : contactName);
        pending.put("company", company);
        pending.put("reviewStatus", SponsorStatus.PENDING);
        pending.put("submittedAt", ctx.opTime());
        pending.put("revision", BigDecimal.ONE);
        pending.put("editedAt", ctx.opTime());
        // The onboarding's lifecycle starts pending (QbAccounts.SPONSOR_ENTITY), as a first application approved at
        // once does; no review is asked: the operator is the one who would review it.
        UUID id = AccountEditing.uuid(ctx.changes().insert(QbAccounts.SPONSOR, pending));
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("reviewStatus", SponsorStatus.APPROVED);
        approved.put("approvedAt", ctx.opTime());
        approved.put("revision", BigDecimal.valueOf(2));
        ctx.changes().update(QbAccounts.SPONSOR, id, 1, approved);

        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("userId", userId);
        assignment.put("operatorId", operatorId);
        assignment.put("assignedAt", ctx.opTime());
        assignment.put("assignedBy", QbOperators.BY_DIRECT);
        assignment.put("reason", "The operator's own sponsor");
        assignment.put("fixed", true);
        ctx.changes().insert(QbOperators.ASSIGNMENT, assignment);
    }
}
