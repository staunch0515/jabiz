package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.inbound.InboundEntities;
import com.jabiz.runtime.task.TaskSpec;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The tasks of payout accounts and transfers for the finance administrators ({@code qb.reconcile}), as
 * {@link TopUpTasks}: one title per reason ({@code task.qb.payout-check.<reason>},
 * {@code task.qb.transfer-check.<reason>}), a source key naming what caused it. A title shows a Stripe account by its
 * last four characters only, and never a link.
 */
final class PayoutTasks {

    static final String ACCOUNT_CHECK = "qb.payout-account-check";
    static final String TRANSFER_CHECK = "qb.transfer-check";

    /** A payout account's task ({@link PayoutChecks#ACCOUNT_REASONS}). */
    static TaskSpec account(String reason, String userId, String accountId, String payoutAccountId, String source,
        String inboundEventId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user", TopUpTasks.shown(userId));
        params.put("account", last4(accountId));
        TaskSpec task = TaskSpec.forPermission(ACCOUNT_CHECK, "task.qb.payout-check." + reason, params,
            QbPermissions.RECONCILE).source(source);
        if (payoutAccountId != null) {
            return task.about(QbPayouts.ACCOUNT, payoutAccountId);
        }
        return inboundEventId == null ? task : task.about(InboundEntities.EVENT, inboundEventId);
    }

    /** A transfer's task ({@link PayoutChecks#TRANSFER_REASONS}). */
    static TaskSpec transfer(String reason, String transferId, BigDecimal amount, String source,
        String inboundEventId) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("transfer", TopUpTasks.shown(transferId));
        params.put("amount", amount == null ? "-" : TopUpTasks.shown(amount.toPlainString()));
        params.put("currency", StripeService.CURRENCY);
        TaskSpec task = TaskSpec.forPermission(TRANSFER_CHECK, "task.qb.transfer-check." + reason, params,
            QbPermissions.RECONCILE).source(source);
        if (transferId != null && StripeIds.is(StripeIds.TOPUP, transferId)) {
            return task.about(QbPayouts.TRANSFER, transferId);
        }
        return inboundEventId == null ? task : task.about(InboundEntities.EVENT, inboundEventId);
    }

    /**
     * Transfers above the review limit skipped because no review rule is in effect: one task a day
     * ({@link #reviewMissingSource}), not one per taker.
     */
    static TaskSpec reviewMissing(Instant at) {
        return TaskSpec.forPermission(TRANSFER_CHECK, "task.qb.transfer-check." + PayoutChecks.REVIEW_NOT_CONFIGURED,
            Map.of("date", day(at)), QbPermissions.RECONCILE).source(reviewMissingSource(at));
    }

    /** {@code transfer-review-missing:<UTC date>}. */
    static String reviewMissingSource(Instant at) {
        return "transfer-review-missing:" + day(at);
    }

    private static String day(Instant at) {
        return at.atOffset(ZoneOffset.UTC).toLocalDate().toString();
    }

    /** {@code transfer:<transfer>:<what>}: the same task when the same thing happens again. */
    static String transferSource(String transferId, String what) {
        return TopUpTasks.eventSource(transferId + ":" + what).replaceFirst("^stripe:", "transfer:");
    }

    /** {@code payout-account:<user>:<what>}. */
    static String accountSource(String userId, String what) {
        return TopUpTasks.eventSource(userId + ":" + what).replaceFirst("^stripe:", "payout-account:");
    }

    /** The last four characters of a Stripe id, as people may see it. */
    static String last4(String id) {
        if (id == null || id.isBlank()) {
            return "-";
        }
        String shown = TopUpTasks.shown(id);
        return shown.length() <= 4 ? "****" : "****" + shown.substring(shown.length() - 4);
    }

    private PayoutTasks() {}
}
