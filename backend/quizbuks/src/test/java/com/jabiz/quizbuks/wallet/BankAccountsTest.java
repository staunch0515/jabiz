package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BankAccountsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");

    @Test
    void normalisesWhatIsTyped() {
        BankAccounts.Account account = BankAccounts.normalise(" ０００５ ", " 三菱ＵＦＪ銀行 ", "１２３", "本店",
            "SAVINGS", "１２３", "ﾐﾂﾋﾞｼ　ﾊﾅｺ");
        assertThat(account.bankCode()).isEqualTo("0005");
        assertThat(account.branchCode()).isEqualTo("123");
        assertThat(account.accountNumber()).isEqualTo("0000123");
        assertThat(account.accountHolder()).isEqualTo("ミツビシ ハナコ");
        assertThat(BankAccounts.problems(account)).isEmpty();
        assertThat(account.toString()).doesNotContain("0000123");
    }

    @Test
    void refusesWhatTheBankWouldNot() {
        BankAccounts.Account account = BankAccounts.normalise("12", "", "12a4", "Branch", "CHECKING", "12345678",
            "Yamada Taro");
        assertThat(BankAccounts.problems(account)).extracting(Violation::field).containsExactlyInAnyOrder("bankCode",
            "branchCode", "bankAccountNumber", "bankName", "accountType", "accountHolder");
        assertThat(BankAccounts.problems(account)).extracting(Violation::ruleCode)
            .containsOnly(WalletCodes.BANK_ACCOUNT_INVALID);
    }

    @Test
    void unavailableForEachReasonInTurn() {
        assertThat(BankAccounts.unavailable(facts(false, false, false, "BANK", NOW.minus(Duration.ofDays(4)), true)))
            .isEqualTo(BankAccounts.OFF);
        assertThat(BankAccounts.unavailable(new BankAccounts.Facts(true, new BigDecimal("1000"), new BigDecimal("-1"),
            new BigDecimal("72"), false, false, false, "BANK", NOW.minus(Duration.ofDays(4)), true, BigDecimal.TEN,
            NOW))).isEqualTo(BankAccounts.INVALID_PARAMS);
        assertThat(BankAccounts.unavailable(facts(true, true, false, "BANK", NOW.minus(Duration.ofDays(4)), true)))
            .isEqualTo(BankAccounts.BANNED);
        assertThat(BankAccounts.unavailable(facts(true, false, false, "STRIPE", NOW.minus(Duration.ofDays(4)), true)))
            .isEqualTo(BankAccounts.NOT_BANK);
        assertThat(BankAccounts.unavailable(facts(true, false, true, "BANK", NOW.minus(Duration.ofDays(4)), true)))
            .isEqualTo(BankAccounts.OPEN_TRANSFER);
        assertThat(BankAccounts.unavailable(facts(true, false, false, "BANK", NOW.minus(Duration.ofHours(71)), true)))
            .isEqualTo(BankAccounts.COOLDOWN);
        assertThat(BankAccounts.unavailable(facts(true, false, false, "BANK", NOW.minus(Duration.ofHours(72)), false)))
            .isEqualTo(BankAccounts.NO_REVIEW);
        assertThat(BankAccounts.unavailable(facts(true, false, false, "BANK", NOW.minus(Duration.ofHours(72)), true)))
            .isNull();
    }

    @Test
    void theAmountFromTheThresholdToWhatMayLeave() {
        BankAccounts.Facts facts = facts(true, false, false, "BANK", NOW, true);
        assertThat(BankAccounts.amountProblem(facts, new BigDecimal("1000"))).isNull();
        assertThat(BankAccounts.amountProblem(facts, new BigDecimal("5000"))).isNull();
        assertThat(BankAccounts.amountProblem(facts, new BigDecimal("999"))).isNotNull();
        assertThat(BankAccounts.amountProblem(facts, new BigDecimal("5001"))).isNotNull();
        assertThat(BankAccounts.amountProblem(facts, new BigDecimal("1500.5"))).isNotNull();
        // A fee at the threshold: the net must stay above nothing.
        BankAccounts.Facts dear = new BankAccounts.Facts(true, new BigDecimal("100"), new BigDecimal("300"),
            new BigDecimal("72"), false, false, false, "BANK", NOW, true, new BigDecimal("5000"), NOW);
        assertThat(BankAccounts.amountProblem(dear, new BigDecimal("300")).params()).containsEntry("min", "301");
    }

    private static BankAccounts.Facts facts(boolean enabled, boolean banned, boolean open, String method,
        Instant changedAt, boolean rule) {
        return new BankAccounts.Facts(enabled, new BigDecimal("1000"), new BigDecimal("200"), new BigDecimal("72"),
            banned, false, open, method, changedAt, rule, new BigDecimal("5000"), NOW);
    }
}
