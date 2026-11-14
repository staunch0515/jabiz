package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.fee.FeeRequests;
import com.jabiz.quizbuks.fee.MoneyClose;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.wallet.ManualCredits;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M15 b (docs/quizbuks/plans/impl/F-M15.md §5.2 L3, §10 MoneyCloseLockOrderIT and MoneyCloseStatementBudgetIT): the
 * locks a wind-up takes (the sponsor's wallet only, the withdrawals of what is under review before it; never
 * {@code qb.account} or {@code qb.quiz}; F-M15c: the sponsor's AI lock before the wallet's, only when an AI credit is
 * closed) and its statements, measured and then fixed here: nothing to do, a deposit to wait for, one invoice to pay,
 * three, one month not invoiced yet, and an AI credit to close. Each counted after a run of the same shape (warm
 * caches).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class MoneyCloseStatementBudgetIT extends MoneyCloseItSupport {

    /**
     * No closure: the process row, the parameters, the read of what is under review, the wallet lock, the read under it,
     * the task's (not closed), the commit. The payment's read is not made.
     */
    static final int NOTHING = 7;
    /** A prepayment still held: as nothing, and the closure's row written. */
    static final int DEPOSIT = 8;
    /**
     * One issued invoice paid from cash, nothing voided: as a deposit, and the months not invoiced (none), the invoices
     * to pay (one read), the payment item (its row, the invoice, the account in one read, the settlement's posting and
     * parts, the invoice's update, the overdue task), the account and the invoices again in one read.
     */
    static final int ONE_INVOICE = 20;
    /** Three issued invoices paid from cash: one payment item each (about eight statements an invoice). */
    static final int THREE_INVOICES = 37;
    /**
     * One issued invoice and an empty account (re-review L1): as a deposit, and the months not invoiced, the account and
     * the invoices in one read; no payment item, no second read (11 before L1).
     */
    static final int NOTHING_TO_PAY = 10;
    /** The current month not invoiced yet: invoiced by its item, then paid. */
    static final int UNINVOICED = 28;
    /**
     * F-M15c: an AI credit bought with cash, nothing else, closed: the process row, the parameters, the read of what
     * is under review (the AI credit with it), the AI lock and the wallet's; the give-back (its row, the credit and the
     * wallets in one read, what it holds by lot, the lots, the posting, the lots and wallets read again for their parts,
     * the writes in one statement); the closure, the months not invoiced, the account, the task, the closure's update,
     * the commit.
     */
    static final int AI_CREDIT = 18;

    @Test
    void anAiCreditIsClosedUnderTheSponsorsAiLockThenTheWallet() {
        String sponsor = withAiCredit("lock");
        erase(sponsor);
        SqlStatementLog.clear();
        assertThat(close(sponsor).outcome()).isEqualTo(MoneyClose.CLOSED);
        List<String> locks = SqlStatementLog.locks();
        System.out.println("[statement-budget] wind-up locks with an AI credit: " + locks);
        // F-M9's order qb.ai:<sponsor> → qb.wallet; never qb.ai:*, qb.account or qb.quiz.
        assertThat(java.util.Set.copyOf(locks)).containsExactlyInAnyOrder("qb.ai:" + sponsor,
            "qb.wallet:SPONSOR:" + sponsor);
        assertThat(locks.getFirst()).isEqualTo("qb.ai:" + sponsor);
    }

    @Test
    void theStatementsOfAWindUpThatClosesAnAiCredit() {
        countedClose(withAiCredit("warm"));
        List<String> ai = countedClose(withAiCredit("measured"));
        System.out.println("[statement-budget] wind-up, an AI credit: " + ai.size() + "\n" + listing(ai));
        assertThat(ai).as("AI credit:\n" + listing(ai)).hasSize(AI_CREDIT);
    }

    /** A sponsor with an AI credit of F-M9 bought with cash, nothing else. */
    private String withAiCredit(String name) {
        String sponsor = newSponsor(unique("s-fm15c-budget-" + name));
        cash(sponsor, 500);
        param(com.jabiz.quizbuks.setup.QbParams.AI_FEE_ENABLED, "true");
        try {
            run(com.jabiz.quizbuks.fee.AiCredits.BUY, sponsorToken(sponsor), body("amount", 400, "requestKey",
                UUID.randomUUID().toString()));
        } finally {
            param(com.jabiz.quizbuks.setup.QbParams.AI_FEE_ENABLED, "false");
        }
        return sponsor;
    }

    @Test
    void theLocksOfAWindUpAreTheWithdrawnApprovalsThenTheWallet() {
        String sponsor = owing(1, false);
        grant(sponsor, 500, clock.instant().plus(Duration.ofDays(60)));
        run(ManualCredits.RECORD, finance(), body("sponsorId", sponsor, "kind", "PAID_OUTSIDE", "amount", 3000,
            "reason", "Paid in cash", "receivedOn", LocalDate.ofInstant(clock.instant(), ZoneId.of("Asia/Tokyo"))
                .toString(), "bankReference", "R-" + UUID.randomUUID().toString().substring(0, 8)));
        run(FeeRequests.RECORD, finance(), body("kind", QbFees.REQUEST_MANUAL, "sponsorId", sponsor, "amount", 500,
            "reason", "Custom report"));
        erase(sponsor);

        SqlStatementLog.clear();
        MoneyClose.CloseOutput run = close(sponsor);
        List<String> locks = SqlStatementLog.locks();
        System.out.println("[statement-budget] wind-up locks: " + locks);
        assertThat(run.withdrawn()).isEqualTo(2);
        assertThat(run.invoicesPaid()).isEqualTo(1);
        assertThat(run.lotsVoided()).isEqualTo(1);
        String wallet = "qb.wallet:SPONSOR:" + sponsor;
        // The withdrawals take no named lock; the invoice's, the payment's and the void's items re-enter the wallet's.
        assertThat(locks).containsExactly(wallet);
    }

    @Test
    void theStatementsOfAWindUp() {
        // Nothing: a sponsor never registered.
        String warm = newSponsor(unique("s-fm15b-budget-none-warm"));
        counted(() -> close(warm));
        String none = newSponsor(unique("s-fm15b-budget-none"));
        List<String> nothing = counted(() -> close(none));

        // A deposit to wait for.
        List<String> deposit = deposit("warm");
        deposit = deposit("measured");
        List<String> depositLocks = SqlStatementLog.locks();

        // One, three issued invoices; a month not invoiced.
        countedClose(owing(1, false));
        List<String> one = countedClose(owing(1, false));
        countedClose(owing(3, false));
        List<String> three = countedClose(owing(3, false));
        countedClose(owing(0, true));
        List<String> uninvoiced = countedClose(owing(0, true));
        List<String> uninvoicedLocks = SqlStatementLog.locks();
        // Re-review L1: an invoice and nothing to pay it with: no payment item, no second read.
        countedClose(broke());
        List<String> nothingToPay = countedClose(broke());

        System.out.println("[statement-budget] wind-up, nothing: " + nothing.size() + "\n" + listing(nothing));
        System.out.println("[statement-budget] wind-up, a deposit: " + deposit.size() + "\n" + listing(deposit));
        System.out.println("[statement-budget] wind-up, one invoice: " + one.size() + "\n" + listing(one));
        System.out.println("[statement-budget] wind-up, three invoices: " + three.size() + "\n" + listing(three));
        System.out.println("[statement-budget] wind-up, a month not invoiced: " + uninvoiced.size() + "\n"
            + listing(uninvoiced));
        assertThat(nothing).as("nothing:\n" + listing(nothing)).hasSize(NOTHING);
        assertThat(deposit).as("deposit:\n" + listing(deposit)).hasSize(DEPOSIT);
        assertThat(one).as("one:\n" + listing(one)).hasSize(ONE_INVOICE);
        assertThat(three).as("three:\n" + listing(three)).hasSize(THREE_INVOICES);
        assertThat(uninvoiced).as("uninvoiced:\n" + listing(uninvoiced)).hasSize(UNINVOICED);
        System.out.println("[statement-budget] wind-up, nothing to pay with: " + nothingToPay.size() + "\n"
            + listing(nothingToPay));
        assertThat(nothingToPay).as("nothing to pay with:\n" + listing(nothingToPay)).hasSize(NOTHING_TO_PAY);
        // Re-review L4: waiting on a deposit, and invoicing a month (its item re-enters it): the wallet's lock only.
        for (List<String> locks : List.of(depositLocks, uninvoicedLocks)) {
            assertThat(locks).isNotEmpty().allMatch(lock -> lock.startsWith("qb.wallet:SPONSOR:"));
            assertThat(java.util.Set.copyOf(locks)).hasSize(1);
        }
    }

    /** An erased-to-be postpaid sponsor with cash and {@code invoices} issued invoices (and the current month's fee). */
    private String owing(int invoices, boolean thisMonth) {
        String sponsor = newSponsor(unique("s-fm15b-budget"));
        billing(sponsor, "POSTPAID", 50_000);
        cash(sponsor, 10_000);
        for (int i = 0; i < invoices; i++) {
            manualFee(sponsor, 300);
            clock.set(clock.instant().plus(Duration.ofDays(32)));
            invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        }
        if (thisMonth) {
            manualFee(sponsor, 300);
        }
        return sponsor;
    }

    /** A postpaid sponsor with one issued invoice and nothing to pay it with. */
    private String broke() {
        String sponsor = newSponsor(unique("s-fm15b-budget-broke"));
        billing(sponsor, "POSTPAID", 50_000);
        manualFee(sponsor, 300);
        clock.set(clock.instant().plus(Duration.ofDays(32)));
        invoicePeriod(sponsor, clock.instant().minus(Duration.ofDays(32)));
        return sponsor;
    }

    private List<String> countedClose(String sponsor) {
        erase(sponsor);
        List<String> statements = counted(() -> close(sponsor));
        assertThat(closure(sponsor)).containsEntry("status", "CLOSED");
        return statements;
    }

    private List<String> deposit(String name) {
        autoApprove(true);
        String sponsor = pricedSponsor("fm15b-budget-" + name);
        grant(sponsor, 1_000, clock.instant().plus(Duration.ofDays(90)));
        String publicationId = audienceDraft(sponsor, "FM15b budget " + name);
        submit(sponsorToken(sponsor), publicationId);
        erase(sponsor);
        List<String> statements = counted(() -> close(sponsor));
        assertThat(closure(sponsor)).containsEntry("reason_code", "DEPOSIT_OPEN");
        return statements;
    }

    private List<String> counted(Supplier<?> action) {
        SqlStatementLog.clear();
        action.get();
        List<List<String>> own = SqlStatementLog.transactions().stream()
            .filter(t -> t.stream().anyMatch(sql -> sql.startsWith("INSERT INTO op_process ("))).toList();
        assertThat(own).as("one transaction").hasSize(1);
        return own.getFirst();
    }

    private static String listing(List<String> statements) {
        return IntStream.range(0, statements.size())
            .mapToObj(i -> String.format("%3d  %s", i + 1, statements.get(i).replaceAll("\\s+", " ")))
            .collect(Collectors.joining("\n"));
    }
}
