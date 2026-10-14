package com.jabiz.quizbuks.wallet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * Whether what Stripe reports fits a top-up (docs/quizbuks/plans/Q6-payments.md, D-Q6-3, D-Q6-4, D-Q6-5). A verified
 * signature says Stripe sent it, not that it belongs to this top-up, so everything is compared with the server's
 * record: the session, the references, the mode, the currency and the amount. Only a session that fits in every
 * respect is credited, and at the record's amount; anything else is a reason for a person to look ({@code CreateTask}),
 * never a failure (the event would be retried for nothing) and never a credit. Pure: the processes read the record
 * under the sponsor's wallet lock and act on the verdict in the same transaction.
 */
public final class TopUpChecks {

    /** The top-up is not found, or the reference is not one of ours. */
    public static final String UNKNOWN_TOPUP = "UNKNOWN_TOPUP";
    /** The event or the session is of the other Stripe mode than this deployment. */
    public static final String LIVEMODE = "LIVEMODE";
    /** Another session than the record's, or references (top-up, sponsor) that do not agree with the record. */
    public static final String SESSION_MISMATCH = "SESSION_MISMATCH";
    /** The record cannot take it: expired or failed already, or paid when the session expired. */
    public static final String STATUS = "STATUS";
    public static final String NOT_PAYMENT = "NOT_PAYMENT";
    public static final String NOT_PAID = "NOT_PAID";
    public static final String NO_PAYMENT_INTENT = "NO_PAYMENT_INTENT";
    public static final String CURRENCY = "CURRENCY";
    public static final String AMOUNT_MISMATCH = "AMOUNT_MISMATCH";
    /** The created checkout does not fit the record (address, reference, amount, expiry). */
    public static final String OPEN_MISMATCH = "OPEN_MISMATCH";
    /** Asking Stripe about a stale top-up failed. */
    public static final String CHECK_FAILED = "CHECK_FAILED";
    /** Stripe's fee does not fit the payment (currency, amounts). */
    public static final String FEE_MISMATCH = "FEE_MISMATCH";
    /** Asking Stripe for the fee failed. */
    public static final String FEE_FAILED = "FEE_FAILED";
    /** Stripe settled the payment in another currency than yen (the setting's): finance books the fee. */
    public static final String FEE_CURRENCY = "FEE_CURRENCY";

    /** Every reason of a top-up's task, each with its title {@code task.qb.topup-check.<reason>}. */
    public static final List<String> REASONS = List.of(UNKNOWN_TOPUP, LIVEMODE, SESSION_MISMATCH, STATUS, NOT_PAYMENT,
        NOT_PAID, NO_PAYMENT_INTENT, CURRENCY, AMOUNT_MISMATCH, OPEN_MISMATCH, CHECK_FAILED, FEE_MISMATCH, FEE_FAILED,
        FEE_CURRENCY);

    /** The latest expiry believed (epoch seconds, year 3000): beyond it the value is not a time at all. */
    static final long LAST_EXPIRY = 32_503_680_000L;

    /** What to do. */
    public enum Outcome {
        /** Credit the record's amount, once. */
        CREDIT,
        /** Mark the top-up expired. */
        EXPIRE,
        /** Done before: nothing changes. */
        ALREADY,
        /** Change nothing; ask a person ({@link Verdict#reason}). */
        CHECK
    }

    public record Verdict(Outcome outcome, String reason) {
        static final Verdict CREDIT = new Verdict(Outcome.CREDIT, null);
        static final Verdict EXPIRE = new Verdict(Outcome.EXPIRE, null);
        static final Verdict ALREADY = new Verdict(Outcome.ALREADY, null);

        static Verdict check(String reason) {
            return new Verdict(Outcome.CHECK, reason);
        }
    }

    /**
     * The record as the checks see it.
     *
     * @param checkoutSessionId null until the checkout was opened
     */
    public record TopUpView(String topUpId, String sponsorId, BigDecimal amount, String status,
        String checkoutSessionId) {}

    /** Stripe's fee of a payment (the job {@code qb.stripe.payment-fee}), in the balance's currency. */
    public record Fee(String chargeId, String balanceTransactionId, BigDecimal amount, BigDecimal fee,
        BigDecimal net, String currency) {}

    /**
     * A completed checkout ({@code checkout.session.completed}, or Stripe's answer about a stale top-up).
     *
     * @param expectedLivemode this deployment's mode
     * @param eventLivemode    the event's own {@code livemode}; null when the session comes from the API
     */
    public static Verdict paid(TopUpView topUp, StripeObjects.CheckoutSession session, boolean expectedLivemode,
        Boolean eventLivemode) {
        String problem = sessionProblem(topUp, session, expectedLivemode, eventLivemode);
        if (problem != null) {
            return Verdict.check(problem);
        }
        if (TopUpStatus.PAID.equals(topUp.status())) {
            // The same session (sessionProblem compared it): the event again, or the check after the event.
            return Verdict.ALREADY;
        }
        if (!TopUpStatus.unsettled(topUp.status())) {
            return Verdict.check(STATUS);
        }
        if (!"payment".equals(session.mode())) {
            return Verdict.check(NOT_PAYMENT);
        }
        if (!"complete".equals(session.status()) || !"paid".equals(session.paymentStatus())) {
            return Verdict.check(NOT_PAID);
        }
        if (!StripeIds.is(StripeIds.PAYMENT_INTENT, session.paymentIntentId())) {
            return Verdict.check(NO_PAYMENT_INTENT);
        }
        if (!StripeService.CURRENCY.equals(session.currency())) {
            return Verdict.check(CURRENCY);
        }
        if (!same(topUp.amount(), session.amountTotal()) || !same(topUp.amount(), session.amountSubtotal())) {
            return Verdict.check(AMOUNT_MISMATCH);
        }
        return Verdict.CREDIT;
    }

    /** An expired checkout ({@code checkout.session.expired}, or Stripe's answer about a stale top-up). */
    public static Verdict expired(TopUpView topUp, StripeObjects.CheckoutSession session, boolean expectedLivemode,
        Boolean eventLivemode) {
        String problem = sessionProblem(topUp, session, expectedLivemode, eventLivemode);
        if (problem != null) {
            return Verdict.check(problem);
        }
        if (TopUpStatus.EXPIRED.equals(topUp.status()) || TopUpStatus.FAILED.equals(topUp.status())) {
            return Verdict.ALREADY;
        }
        if (!TopUpStatus.unsettled(topUp.status()) || !"expired".equals(session.status())) {
            // Paid, yet expired: should not happen.
            return Verdict.check(STATUS);
        }
        return Verdict.EXPIRE;
    }

    /**
     * Why the checkout the job created does not fit the opening top-up; null when it does. Only an opening top-up is
     * opened: the caller does nothing to one in another state.
     *
     * @param urlPrefix what the checkout's address must start with ({@link StripeSettings#checkoutUrlPrefix})
     */
    public static String openProblem(TopUpView topUp, StripeObjects.CheckoutSession created, boolean expectedLivemode,
        String urlPrefix) {
        if (topUp == null) {
            return UNKNOWN_TOPUP;
        }
        if (!Boolean.valueOf(expectedLivemode).equals(created.livemode())
            || !StripeIds.sessionOfMode(created.id(), expectedLivemode)) {
            return LIVEMODE;
        }
        String url = created.url();
        boolean urlFits = url != null && url.length() <= QbTopUps.URL_LENGTH && url.startsWith(urlPrefix)
            && StripeUrls.plain(url);
        if (!StripeIds.is(StripeIds.CHECKOUT_SESSION, created.id()) || !urlFits
            || !topUp.topUpId().equals(created.clientReferenceId())
            || !topUp.topUpId().equals(created.metadataTopUpId())
            || !topUp.sponsorId().equals(created.metadataSponsorId())
            || !"payment".equals(created.mode())
            || !StripeService.CURRENCY.equals(created.currency()) || !same(topUp.amount(), created.amountTotal())
            || created.expiresAt() == null || created.expiresAt() <= 0 || created.expiresAt() > LAST_EXPIRY) {
            return OPEN_MISMATCH;
        }
        return null;
    }

    /**
     * Why Stripe's fee does not fit the paid top-up; null when it does.
     *
     * @param settlementCurrency the Stripe account's balance currency ({@link StripeSettings#settlementCurrency}):
     *                           the balance transaction must be in it; only a yen one is checked against the payment
     *                           and posted ({@link #FEE_CURRENCY} otherwise)
     */
    public static String feeProblem(TopUpView topUp, Fee fee, String settlementCurrency) {
        if (topUp == null) {
            return UNKNOWN_TOPUP;
        }
        if (!TopUpStatus.PAID.equals(topUp.status())) {
            return STATUS;
        }
        if (fee == null || fee.currency() == null || !fee.currency().equals(settlementCurrency)
            || !StripeIds.is(StripeIds.CHARGE, fee.chargeId())
            || !StripeIds.is(StripeIds.BALANCE_TRANSACTION, fee.balanceTransactionId())
            || !whole(fee.fee()) || !whole(fee.net()) || fee.fee().signum() < 0 || fee.net().signum() < 0) {
            return FEE_MISMATCH;
        }
        if (!StripeService.CURRENCY.equals(settlementCurrency)) {
            return FEE_CURRENCY;
        }
        if (!same(topUp.amount(), fee.amount()) || fee.fee().add(fee.net()).compareTo(topUp.amount()) != 0) {
            return FEE_MISMATCH;
        }
        return null;
    }

    /** What makes a session not the top-up's own: the record, the mode, the session and the references. */
    static String sessionProblem(TopUpView topUp, StripeObjects.CheckoutSession session, boolean expectedLivemode,
        Boolean eventLivemode) {
        if (topUp == null || session == null) {
            return UNKNOWN_TOPUP;
        }
        if ((eventLivemode != null && eventLivemode != expectedLivemode)
            || !Boolean.valueOf(expectedLivemode).equals(session.livemode())
            || !StripeIds.sessionOfMode(session.id(), expectedLivemode)) {
            return LIVEMODE;
        }
        if (!StripeIds.is(StripeIds.CHECKOUT_SESSION, session.id())
            || (topUp.checkoutSessionId() != null && !topUp.checkoutSessionId().equals(session.id()))
            || !topUp.topUpId().equals(session.clientReferenceId())
            || !topUp.topUpId().equals(session.metadataTopUpId())
            || !Objects.equals(topUp.sponsorId(), session.metadataSponsorId())) {
            return SESSION_MISMATCH;
        }
        return null;
    }

    static boolean same(BigDecimal expected, BigDecimal actual) {
        return expected != null && actual != null && expected.compareTo(actual) == 0;
    }

    private static boolean whole(BigDecimal value) {
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private TopUpChecks() {}
}
