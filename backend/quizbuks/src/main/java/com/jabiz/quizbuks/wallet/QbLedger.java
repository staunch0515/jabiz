package com.jabiz.quizbuks.wallet;

import java.util.List;

/**
 * The ledger of QuizBuks (docs/quizbuks/02-design.md section 4): the wallets are the platform ledger, kept in JPY
 * without decimals ({@code jabiz.ledger.*}) and shown as Kudos. {@code QB_SETUP} opens these accounts through
 * {@code LEDGER_ACCOUNT_OPEN}.
 */
public final class QbLedger {

    public static final String STRIPE_BALANCE = "1110";
    public static final String BANK = "1120";
    public static final String SPONSOR_PREPAYMENTS = "2110";
    public static final String REWARDS_PAYABLE = "2120";
    public static final String TRANSFERS_IN_TRANSIT = "2130";
    public static final String SERVICE_REVENUE = "4110";
    public static final String PAYMENT_FEES = "5110";
    /** Kudos granted by hand with no money received (Q18b): the platform's promotion expense. */
    public static final String PROMOTION_EXPENSES = "5120";
    /** Q48b: what the platform owes operators of their settled months, and the expense it is. */
    public static final String OPERATOR_SHARES_PAYABLE = "2140";
    public static final String OPERATOR_SHARES = "5130";
    /**
     * Q87a (D-Q87-1): the sponsors' cash accounts, apart from their Kudos (2110): money a sponsor has with the
     * platform, its use decided when it is turned into Kudos or (Q87b) pays a fee. Its lines carry the sponsor.
     */
    public static final String SPONSOR_CASH = "2160";
    /** Q87b: the platform's fees receivable (by sponsor), the fee revenue and the consumption tax on it. */
    public static final String FEES_RECEIVABLE = "1130";
    public static final String PLATFORM_FEE_REVENUE = "4120";
    public static final String TAX_PAYABLE = "2150";
    /**
     * Q88b (D-Q88-11): the entry fees takers paid at the start of their attempts, owed to the sponsors until the daily
     * job settles them into their cash accounts (2160). Its lines carry the sponsor as the party and the publication.
     */
    public static final String ENTRY_FEES_UNSETTLED = "2170";
    /**
     * F-M1: the sponsors' platform fee Kudos (the fee credit granted to them, F-M2), a sub-account of the account that
     * pays the platform's fees beside the cash (2160): spent before the cash, only on platform fees, never on rewards,
     * never turned into Kudos or money. Its lines carry the sponsor; each grant is a lot ({@code QbFeeCreditGrant}).
     */
    public static final String FEE_CREDIT = "2190";
    /** F-M2: what the platform spends granting platform fee Kudos (granted less voided), apart from 5120's Kudos. */
    public static final String FEE_CREDIT_PROMOTION = "5140";

    /**
     * F-M5: what publications' submissions set aside of the sponsors' accounts for their fees (and, F-M9, AI
     * credit): owed to the sponsors until a fee is paid from it or it goes back. Its lines carry the sponsor, the
     * publication and the purpose; each is a {@code QbPrepayment}.
     */
    public static final String FEE_PREPAYMENTS = "2180";

    /** The name of the dimension that carries the party (a user's id) on the wallet accounts. */
    public static final String PARTY = "party";

    /** An account of the chart: code, name (the ledger keeps one name) and the platform's account type. */
    public record Account(String code, String name, String type) {}

    /** The chart of accounts of section 4.1. */
    public static final List<Account> ACCOUNTS = List.of(
        new Account(STRIPE_BALANCE, "Stripe balance", "ASSET"),
        new Account(BANK, "Bank deposits", "ASSET"),
        new Account(SPONSOR_PREPAYMENTS, "Sponsor prepayments (sponsor wallets)", "LIABILITY"),
        new Account(REWARDS_PAYABLE, "Rewards payable to users (user wallets)", "LIABILITY"),
        new Account(TRANSFERS_IN_TRANSIT, "Transfers in transit", "LIABILITY"),
        new Account(SERVICE_REVENUE, "Platform service revenue (commission)", "REVENUE"),
        new Account(PAYMENT_FEES, "Payment fees (Stripe)", "EXPENSE"),
        new Account(PROMOTION_EXPENSES, "Promotion expenses (Kudos granted)", "EXPENSE"),
        new Account(OPERATOR_SHARES_PAYABLE, "Operator shares payable", "LIABILITY"),
        new Account(OPERATOR_SHARES, "Operator shares (expense)", "EXPENSE"),
        new Account(SPONSOR_CASH, "Sponsor cash accounts", "LIABILITY"),
        new Account(FEES_RECEIVABLE, "Platform fees receivable", "ASSET"),
        new Account(PLATFORM_FEE_REVENUE, "Platform fee revenue", "REVENUE"),
        new Account(TAX_PAYABLE, "Consumption tax payable", "LIABILITY"),
        new Account(ENTRY_FEES_UNSETTLED, "Entry fees unsettled", "LIABILITY"),
        new Account(FEE_CREDIT, "Sponsor platform fee Kudos (fee credit)", "LIABILITY"),
        new Account(FEE_CREDIT_PROMOTION, "Promotion expenses (platform fee Kudos granted)", "EXPENSE"),
        new Account(FEE_PREPAYMENTS, "Platform fee prepayments", "LIABILITY"));

    private QbLedger() {}
}
