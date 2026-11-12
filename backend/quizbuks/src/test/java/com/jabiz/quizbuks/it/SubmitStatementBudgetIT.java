package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.FeeProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M5 (M6 of its review): how many statements a publication's submission sends in its transaction, with the funds
 * check and the prepayment (docs/quizbuks/plans/impl/F-M.md §8.3), and which locks it takes, in order. Each case runs
 * once before it is counted (warm caches), as {@link AttemptStatementBudgetIT}. A change of the submission's path
 * changes these numbers on purpose: say why in the change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = "it.sql-log.enabled=true")
class SubmitStatementBudgetIT extends PrepaymentItSupport {

    /**
     * Nothing set aside (no audience rule) [29 before F-M5 / 31]: the submission as before, then the sponsor's wallet
     * lock (after the quiz's and the publication's) and the one read under it (the sponsor's billing and credit limit,
     * the fee credit, the cash wallet and lots, the open postpaid prepayments, the criteria, the rules and the
     * operator; the reads keyed by nothing send no SQL).
     */
    static final int NOTHING_SET_ASIDE = 31;
    /**
     * A prepaid sponsor with an audience rule [38]: the above, the operator's price list and its prices (two reads),
     * and the freeze: its operation record, its read of the account, the prepayment (written first: the posting names
     * it), the posting with its parts, the lots and the wallet (with the publication's writes, held to the commit).
     */
    static final int PREPAID_SET_ASIDE = 38;
    /**
     * A postpaid sponsor with an audience rule [36]: the two price reads, what they owe ({@code qb.system.fee-receivable},
     * 1130 for them), and the freeze: its operation record, its read, the prepayment; no posting, no parts.
     */
    static final int POSTPAID_SET_ASIDE = 36;

    @Test
    void theSubmissionsStatements() {
        autoApprove(false);
        // Nothing set aside.
        String plain = pricedSponsor("fm5-budget-plain");
        submitCounted(plain, publication(plain, "warm plain", false));
        List<String> nothing = submitCounted(plain, publication(plain, "plain", false));

        // Prepaid, the audience fee set aside of granted credit.
        String prepaid = pricedSponsor("fm5-budget-prepaid");
        grant(prepaid, 10_000, clock.instant().plus(Duration.ofDays(90)));
        submitCounted(prepaid, publication(prepaid, "warm prepaid", true));
        List<String> setAside = submitCounted(prepaid, publication(prepaid, "prepaid", true));
        List<String> prepaidLocks = com.jabiz.quizbuks.it.SqlStatementLog.locks();
        // The lock order (backend/quizbuks/CLAUDE.md): the quiz, the publication, then the sponsor's wallet, last.
        assertThat(prepaidLocks).hasSize(3);
        assertThat(prepaidLocks.get(0)).startsWith("qb.quiz:");
        assertThat(prepaidLocks.get(1)).startsWith("qb.pub:");
        assertThat(prepaidLocks.get(2)).isEqualTo("qb.wallet:SPONSOR:" + prepaid);

        // Postpaid: what they owe is read, nothing is posted.
        String postpaid = pricedSponsor("fm5-budget-postpaid");
        run(FeeProcesses.BILLING_SET, TestTokens.bearer(tokens, "fm5-budget-billing", QbPermissions.FEE_BILLING),
            body("userId", postpaid, "billingMode", "POSTPAID", "creditLimit", 100_000, "reason", "F-M5"));
        submitCounted(postpaid, publication(postpaid, "warm postpaid", true));
        List<String> owed = submitCounted(postpaid, publication(postpaid, "postpaid", true));

        System.out.println("[statement-budget] submit, nothing set aside: " + nothing.size() + "\n" + listing(nothing));
        System.out.println("[statement-budget] submit, prepaid: " + setAside.size() + "\n" + listing(setAside));
        System.out.println("[statement-budget] submit, postpaid: " + owed.size() + "\n" + listing(owed));
        System.out.println("[statement-budget] locks: " + prepaidLocks);
        assertThat(nothing).as("nothing set aside:\n" + listing(nothing)).hasSize(NOTHING_SET_ASIDE);
        assertThat(setAside).as("prepaid:\n" + listing(setAside)).hasSize(PREPAID_SET_ASIDE);
        assertThat(owed).as("postpaid:\n" + listing(owed)).hasSize(POSTPAID_SET_ASIDE);
    }

    /** A complete draft of the sponsor's, with the audience rule or without. */
    String publication(String sponsor, String name, boolean audience) {
        if (audience) {
            return audienceDraft(sponsor, name);
        }
        String token = sponsorToken(sponsor);
        return draft(token, version(token, name + " quiz"), name);
    }

    /** Submits it; the statements of the submission's own transaction (the one recording its operation). */
    List<String> submitCounted(String sponsor, String publicationId) {
        com.jabiz.quizbuks.it.SqlStatementLog.clear();
        submit(sponsorToken(sponsor), publicationId);
        List<List<String>> own = com.jabiz.quizbuks.it.SqlStatementLog.transactions().stream()
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
