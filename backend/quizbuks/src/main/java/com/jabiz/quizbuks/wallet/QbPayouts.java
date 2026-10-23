package com.jabiz.quizbuks.wallet;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.TemporalRole;
import com.jabiz.quizbuks.QbPermissions;

import java.math.BigDecimal;

/**
 * Payout accounts, transfers and the platform's bank payouts (docs/quizbuks/plans/Q6-payments.md, Q6b), all ordinary
 * tables written only by the processes ({@code processOnlyWrites}); the platform's audit keeps their history.
 * <ul>
 *   <li>{@value #ACCOUNT}: a taker's Stripe Express account (one per user, and one user per Stripe account: both
 *       unique in the database, so no Stripe account can ever be recorded for a second user). An ordinary table
 *       rather than the temporal one the plan named: Stripe's events find it by the account id, and only a table
 *       constraint keeps an account to one user whatever happens (review of Q6b).</li>
 *   <li>{@value #ONBOARDING}: the user's latest link to Stripe's onboarding, short-lived, overwritten.</li>
 *   <li>{@value #TRANSFER}: one automatic transfer: at most one unfinished per user (a partial unique index), its
 *       postings and Stripe's transfer each unique.</li>
 *   <li>{@value #STRIPE_PAYOUT}: a payout of the platform's Stripe balance to its bank (D-Q6-13), once per payout.</li>
 *   <li>{@value #BANK_ACCOUNT}: a taker's Japanese bank account and how they are paid (Q13b): the daily Stripe
 *       transfer, or withdrawals to the bank they ask for. One per user, and one user per bank account (both unique in
 *       the database). A withdrawal is a transfer of method {@code BANK} with a copy of the account.</li>
 * </ul>
 * Stripe's account ids are masked ({@code LAST4}) for everyone without {@code qb.admin.finance.read}; bank account
 * numbers for everyone without {@code qb.payout.bank.reveal} (finance reveals one value at a time to pay it).
 */
public final class QbPayouts {

    public static final String ACCOUNT = "QbPayoutAccount";
    public static final String ONBOARDING = "QbPayoutOnboarding";
    public static final String TRANSFER = "QbTransfer";
    public static final String STRIPE_PAYOUT = "QbStripePayout";
    public static final String BANK_ACCOUNT = "QbBankAccount";

    public static final String ACCOUNT_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:payout-account-status";
    public static final String TRANSFER_STATUS_DICTIONARY = "urn:jabiz:dict:quizbuks:transfer-status";
    public static final String BANK_ACCOUNT_TYPE_DICTIONARY = "urn:jabiz:dict:quizbuks:bank-account-type";
    public static final String PAYOUT_METHOD_DICTIONARY = "urn:jabiz:dict:quizbuks:payout-method";

    /** How a taker is paid (Q13b), and the method of a transfer. */
    public static final String STRIPE = "STRIPE";
    public static final String BANK = "BANK";
    public static final java.util.List<String> METHODS = java.util.List.of(STRIPE, BANK);
    /** The kinds of a Japanese bank account: 普通, 当座, 貯蓄. */
    public static final java.util.List<String> ACCOUNT_TYPES = java.util.List.of("ORDINARY", "CURRENT", "SAVINGS");

    /** The caller's own payout account, onboarding link and transfers, read only. */
    public static final String MY_ACCOUNT = "urn:jabiz:dataset:taker:" + ACCOUNT;
    public static final String MY_ONBOARDING = "urn:jabiz:dataset:taker:" + ONBOARDING;
    public static final String MY_TRANSFERS = "urn:jabiz:dataset:taker:" + TRANSFER;
    public static final String MY_BANK_ACCOUNT = "urn:jabiz:dataset:taker:" + BANK_ACCOUNT;

    /** Stripe Connect, the only provider (D-Q6-10). */
    public static final String PROVIDER = "STRIPE_CONNECT";

    /** Events of transfers: changed (any state, for the wallet's sync), sent and failed (Q7's messages). */
    public static final String CHANGED_EVENT = "qb.transfer.changed";
    public static final String SENT_EVENT = "qb.transfer.sent";
    public static final String FAILED_EVENT = "qb.transfer.failed";

    /** The approval subject of transfers above the review limit, and the rule {@code QB_SETUP} proposes. */
    public static final String APPROVAL_SUBJECT = "qb.transfer";
    public static final String REVIEW_RULE = "qb.transfer.review";

    static final int STRIPE_ID_LENGTH = 255;
    static final int REQUIREMENTS_LENGTH = 1000;

    public static final EntityDefinition ACCOUNT_ENTITY = EntityDefinition.define(ACCOUNT, eb -> {
        eb.physicalTable("qb_payout_account");
        eb.primaryKey("payoutAccountId");
        eb.field("payoutAccountId", f -> f.physicalColumn("payout_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:payout-account"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("provider", f -> f.physicalColumn("provider").immutable(true).required(true).processOnly()
            .asText(20));
        eb.field("externalAccountId", f -> f.physicalColumn("external_account_id").immutable(true).required(true)
            .processOnly().asText(STRIPE_ID_LENGTH).masked(QbPermissions.ADMIN_FINANCE_READ, MaskStyle.LAST4));
        eb.field("country", f -> f.physicalColumn("country").processOnly().asText(2));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(ACCOUNT_STATUS_DICTIONARY, PayoutAccountStatus.ALL.toArray(String[]::new)));
        eb.field("payoutsEnabled", f -> f.physicalColumn("payouts_enabled").required(true).processOnly().asBool());
        eb.field("detailsSubmitted", f -> f.physicalColumn("details_submitted").required(true).processOnly()
            .asBool());
        eb.field("requirementsDue", f -> f.physicalColumn("requirements_due").processOnly()
            .asText(REQUIREMENTS_LENGTH));
        // When Stripe last reported the account: older reports are ignored (events come in any order).
        eb.field("stripeUpdatedAt", f -> f.physicalColumn("stripe_updated_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("livemode", f -> f.physicalColumn("livemode").required(true).processOnly().asBool());
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_payout_account_user", "userId");
        eb.unique("uk_qb_payout_account_external", "externalAccountId");
        eb.listView("default", lv -> lv
            .columns("userId", "externalAccountId", "country", "status", "payoutsEnabled", "detailsSubmitted",
                "stripeUpdatedAt", "createdAt")
            .filters("userId", "status", "createdAt")
            .sorts("createdAt", "stripeUpdatedAt")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition ONBOARDING_ENTITY = EntityDefinition.define(ONBOARDING, eb -> {
        eb.physicalTable("qb_payout_onboarding");
        eb.primaryKey("onboardingId");
        eb.field("onboardingId", f -> f.physicalColumn("onboarding_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:payout-onboarding"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(128));
        // The link is the user's alone (only their own dataset gives it); kept out of the audit.
        eb.field("url", f -> f.physicalColumn("url").required(true).processOnly().auditMasked()
            .asText(QbTopUps.URL_LENGTH));
        eb.field("expiresAt", f -> f.physicalColumn("expires_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_payout_onboarding_user", "userId");
        eb.listView("default", lv -> lv
            .columns("userId", "expiresAt")
            .filters("userId")
            .sorts("expiresAt")
            .defaultSort("expiresAt", false));
    });

    public static final EntityDefinition TRANSFER_ENTITY = EntityDefinition.define(TRANSFER, eb -> {
        eb.physicalTable("qb_transfer");
        eb.primaryKey("transferId");
        eb.field("transferId", f -> f.physicalColumn("transfer_id").immutable(true).required(true).generated(true)
            .asSemanticIdentity("urn:jabiz:entity:quizbuks:transfer"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(128));
        eb.field("externalAccountId", f -> f.physicalColumn("external_account_id").immutable(true).required(true)
            .processOnly().asText(STRIPE_ID_LENGTH).masked(QbPermissions.ADMIN_FINANCE_READ, MaskStyle.LAST4));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("fee", f -> f.physicalColumn("fee").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("net", f -> f.physicalColumn("net").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly()
            .asCode(TRANSFER_STATUS_DICTIONARY, TransferStatus.ALL.toArray(String[]::new)));
        eb.field("approvalRequestId", f -> f.physicalColumn("approval_request_id").processOnly().asText(64));
        eb.field("stripeTransferId", f -> f.physicalColumn("stripe_transfer_id").processOnly()
            .asText(STRIPE_ID_LENGTH));
        eb.field("sendTransactionId", f -> f.physicalColumn("send_transaction_id").processOnly().asText(64));
        eb.field("settleTransactionId", f -> f.physicalColumn("settle_transaction_id").processOnly().asText(64));
        eb.field("reversalTransactionId", f -> f.physicalColumn("reversal_transaction_id").processOnly()
            .asText(64));
        eb.field("reversedAmount", f -> f.physicalColumn("reversed_amount").required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("failureCode", f -> f.physicalColumn("failure_code").processOnly().asText(100));
        eb.field("failureMessage", f -> f.physicalColumn("failure_message").processOnly().asText(200));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("sentAt", f -> f.physicalColumn("sent_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("failedAt", f -> f.physicalColumn("failed_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        // When Stripe was last asked about it while in doubt (the hourly check asks again after an hour).
        eb.field("checkRequestedAt", f -> f.physicalColumn("check_requested_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // When the sending job reported its end (result or failure): only a lookup well after it says "none" for sure.
        eb.field("sendEndedAt", f -> f.physicalColumn("send_ended_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // Who released the taker held after this transfer (rejected or reversed), and when.
        eb.field("releasedBy", f -> f.physicalColumn("released_by").processOnly().asText(128));
        eb.field("releasedAt", f -> f.physicalColumn("released_at").processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        // Q13b: a withdrawal to a bank account, with the account as it was asked for (never changed), and how finance
        // paid it or why it came back.
        eb.field("method", f -> f.physicalColumn("method").immutable(true).required(true).processOnly()
            .asCode(PAYOUT_METHOD_DICTIONARY, METHODS.toArray(String[]::new)));
        bankFields(eb, true);
        eb.field("paidOn", f -> f.physicalColumn("paid_on").processOnly().asDate());
        eb.field("paidReference", f -> f.physicalColumn("paid_reference").processOnly()
            .asText(BankTransfers.REFERENCE_LENGTH));
        eb.field("paidBy", f -> f.physicalColumn("paid_by").processOnly().asText(128));
        eb.field("returnedBy", f -> f.physicalColumn("returned_by").processOnly().asText(128));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_transfer_stripe", "stripeTransferId");
        eb.stateTransitions("status", st -> st
            .initial(TransferStatus.REVIEW, TransferStatus.SENDING)
            .from(TransferStatus.REVIEW).to(TransferStatus.SENDING, TransferStatus.REJECTED)
            .from(TransferStatus.SENDING).to(TransferStatus.SENT, TransferStatus.FAILED, TransferStatus.CHECK)
            .from(TransferStatus.CHECK).to(TransferStatus.SENT, TransferStatus.FAILED)
            .from(TransferStatus.SENT).to(TransferStatus.REVERSED));
        eb.listView("default", lv -> lv
            .columns("userId", "method", "amount", "fee", "net", "status", "createdAt", "sentAt", "failureCode")
            .filters("userId", "method", "status", "createdAt", "sentAt")
            .sorts("createdAt", "sentAt", "amount")
            .defaultSort("createdAt", false));
    });

    public static final EntityDefinition STRIPE_PAYOUT_ENTITY = EntityDefinition.define(STRIPE_PAYOUT, eb -> {
        eb.physicalTable("qb_stripe_payout");
        eb.primaryKey("payoutRowId");
        eb.field("payoutRowId", f -> f.physicalColumn("payout_row_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:stripe-payout"));
        eb.field("payoutId", f -> f.physicalColumn("payout_id").immutable(true).required(true).processOnly()
            .asText(STRIPE_ID_LENGTH));
        eb.field("amount", f -> f.physicalColumn("amount").immutable(true).required(true).processOnly()
            .asMonetary("JPY", 0));
        eb.field("status", f -> f.physicalColumn("status").required(true).processOnly().asText(20));
        eb.field("paidAt", f -> f.physicalColumn("paid_at").processOnly().asTemporal(TemporalRole.EVENT_TIME));
        eb.field("ledgerTransactionId", f -> f.physicalColumn("ledger_transaction_id").processOnly().asText(64));
        eb.field("reversalTransactionId", f -> f.physicalColumn("reversal_transaction_id").processOnly()
            .asText(64));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_stripe_payout_id", "payoutId");
        eb.listView("default", lv -> lv
            .columns("payoutId", "amount", "status", "paidAt")
            .filters("payoutId", "status", "paidAt")
            .sorts("paidAt", "amount")
            .defaultSort("paidAt", false));
    });

    public static final EntityDefinition BANK_ACCOUNT_ENTITY = EntityDefinition.define(BANK_ACCOUNT, eb -> {
        eb.physicalTable("qb_bank_account");
        eb.primaryKey("bankAccountId");
        eb.field("bankAccountId", f -> f.physicalColumn("bank_account_id").immutable(true).required(true)
            .generated(true).asSemanticIdentity("urn:jabiz:entity:quizbuks:bank-account"));
        eb.field("userId", f -> f.physicalColumn("user_id").immutable(true).required(true).processOnly()
            .asText(128));
        bankFields(eb, false);
        eb.field("payoutMethod", f -> f.physicalColumn("payout_method").required(true).processOnly()
            .asCode(PAYOUT_METHOD_DICTIONARY, METHODS.toArray(String[]::new)));
        // When the account was last changed: no withdrawal to it until the cooling-off period is over.
        eb.field("changedAt", f -> f.physicalColumn("changed_at").required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("createdAt", f -> f.physicalColumn("created_at").immutable(true).required(true).processOnly()
            .asTemporal(TemporalRole.EVENT_TIME));
        eb.field("version", f -> f.physicalColumn("version").asVersion());
        eb.unique("uk_qb_bank_account_user", "userId");
        eb.listView("default", lv -> lv
            .columns("userId", "bankName", "branchName", "accountType", "accountNumber", "accountHolder",
                "payoutMethod", "changedAt")
            .filters("userId", "payoutMethod")
            .sorts("changedAt")
            .defaultSort("changedAt", false));
    });

    /**
     * A Japanese bank account's fields, on the taker's account and copied onto a withdrawal ({@code nullable} there:
     * a Stripe transfer has none). The number is masked; the holder's name is not: the reviewer compares it.
     */
    private static void bankFields(com.jabiz.entity.EntityBuilder eb, boolean onTransfer) {
        eb.field("bankCode", f -> bank(f.physicalColumn("bank_code"), onTransfer).asText(4));
        eb.field("bankName", f -> bank(f.physicalColumn("bank_name"), onTransfer).asText(BankAccounts.NAME_LENGTH));
        eb.field("branchCode", f -> bank(f.physicalColumn("branch_code"), onTransfer).asText(3));
        eb.field("branchName", f -> bank(f.physicalColumn("branch_name"), onTransfer)
            .asText(BankAccounts.NAME_LENGTH));
        eb.field("accountType", f -> bank(f.physicalColumn("account_type"), onTransfer)
            .asCode(BANK_ACCOUNT_TYPE_DICTIONARY, ACCOUNT_TYPES.toArray(String[]::new)));
        eb.field("accountNumber", f -> bank(f.physicalColumn("account_number"), onTransfer).asText(7)
            .masked(QbPermissions.PAYOUT_BANK_REVEAL, MaskStyle.LAST4));
        eb.field("accountHolder", f -> bank(f.physicalColumn("account_holder"), onTransfer)
            .asText(BankAccounts.HOLDER_LENGTH));
    }

    private static com.jabiz.entity.FieldBuilder bank(com.jabiz.entity.FieldBuilder f, boolean onTransfer) {
        return onTransfer ? f.immutable(true).processOnly() : f.required(true).processOnly();
    }

    /**
     * The approval subject of transfers (D-Q6-12): the rule asks for {@code qb.payout.review} when
     * {@code needsReview}. A case's id is the transfer; its preparer the payee, so the platform refuses the payee's
     * own approval.
     */
    public static ApprovalSubject approvalSubject() {
        return ApprovalSubject.define(APPROVAL_SUBJECT, s -> s.entity(TRANSFER).bool("needsReview")
            .number("amount").text("userId"));
    }

    public static String defaultDataset(String entity) {
        return "urn:jabiz:dataset:default:" + entity;
    }

    static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString());
    }

    private QbPayouts() {}
}
