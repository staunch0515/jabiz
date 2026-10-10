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
        new Account(PAYMENT_FEES, "Payment fees (Stripe)", "EXPENSE"));

    private QbLedger() {}
}
