package com.jabiz.quizbuks.wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class PayoutChecksTest {

    static final String TRANSFER = "01890000-0000-7000-8000-0000000000aa";
    static final String PREFIX = "https://connect.stripe.com/";

    static Map<String, Object> transfer(Consumer<Map<String, Object>> change) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", "tr_1");
        t.put("object", "transfer");
        t.put("amount", new BigDecimal("1000"));
        t.put("amount_reversed", BigDecimal.ZERO);
        t.put("currency", "jpy");
        t.put("destination", "acct_1");
        t.put("transfer_group", "qb-" + TRANSFER);
        t.put("metadata", Map.of("transferId", TRANSFER));
        t.put("livemode", false);
        change.accept(t);
        return t;
    }

    static String problem(Consumer<Map<String, Object>> change) {
        return PayoutChecks.transferProblem(TRANSFER, new BigDecimal("1000"), "acct_1",
            PayoutObjects.transfer(transfer(change)), false);
    }

    @Test
    void aTransferMustBeTheRecordsInEveryRespect() {
        assertThat(problem(t -> {})).isNull();
        assertThat(problem(t -> t.put("amount", new BigDecimal("1001")))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("amount", "1000"))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("currency", "usd"))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("destination", "acct_2"))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("transfer_group", "qb-other"))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("livemode", true))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(problem(t -> t.put("id", "po_1"))).isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
        assertThat(PayoutChecks.transferProblem(TRANSFER, new BigDecimal("1000"), "acct_1", null, false))
            .isEqualTo(PayoutChecks.TRANSFER_MISMATCH);
    }

    @Test
    void anOnboardingMustNameAnAccountOfTheModeAndStripesLink() {
        PayoutObjects.Onboarding ok = new PayoutObjects.Onboarding("acct_1", "JP", false, PREFIX + "setup/e/x",
            1_800_000_000L);
        assertThat(PayoutChecks.onboardingProblem(ok, false, PREFIX)).isNull();
        assertThat(PayoutChecks.onboardingProblem(ok, true, PREFIX)).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        assertThat(PayoutChecks.onboardingProblem(new PayoutObjects.Onboarding("acct_1", "JP", false,
            "https://evil.example.com/x", 1_800_000_000L), false, PREFIX)).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        assertThat(PayoutChecks.onboardingProblem(new PayoutObjects.Onboarding("cus_1", "JP", false,
            PREFIX + "x", 1_800_000_000L), false, PREFIX)).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        assertThat(PayoutChecks.onboardingProblem(new PayoutObjects.Onboarding("acct_1", "JP", false,
            PREFIX + "x", null), false, PREFIX)).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        assertThat(PayoutChecks.onboardingProblem(null, false, PREFIX)).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        // As for a checkout's address: a link with whitespace or control characters is not followed.
        for (String bad : List.of("setup/e/x y", "setup/e/x\ty", "setup/e/x\n", "setup/e/\u0000", "setup/e/\u007f")) {
            assertThat(PayoutChecks.onboardingProblem(new PayoutObjects.Onboarding("acct_1", "JP", false,
                PREFIX + bad, 1_800_000_000L), false, PREFIX)).as(bad).isEqualTo(PayoutChecks.ONBOARD_MISMATCH);
        }
        // The link is the user's: never in a log line.
        assertThat(ok.toString()).doesNotContain("setup");
    }

    @Test
    void anAccountsStatusIsWhatStripeSays() {
        Map<String, Object> account = new LinkedHashMap<>();
        account.put("id", "acct_1");
        account.put("country", "JP");
        account.put("payouts_enabled", true);
        account.put("details_submitted", true);
        account.put("capabilities", Map.of("transfers", "active"));
        account.put("requirements", Map.of("currently_due", List.of("individual.dob.day", 5)));
        account.put("metadata", Map.of("userId", "u1"));
        account.put("livemode", false);
        PayoutObjects.Account read = PayoutObjects.account(account);
        assertThat(PayoutAccountStatus.of(read)).isEqualTo(PayoutAccountStatus.ENABLED);
        assertThat(read.requirementsDue()).containsExactly("individual.dob.day");
        assertThat(read.metadataUserId()).isEqualTo("u1");
        account.put("capabilities", Map.of("transfers", "pending"));
        assertThat(PayoutAccountStatus.of(PayoutObjects.account(account))).isEqualTo(PayoutAccountStatus.RESTRICTED);
        account.put("details_submitted", false);
        assertThat(PayoutAccountStatus.of(PayoutObjects.account(account))).isEqualTo(PayoutAccountStatus.PENDING);
        account.put("payouts_enabled", "true");
        account.put("capabilities", Map.of("transfers", "active"));
        assertThat(PayoutAccountStatus.of(PayoutObjects.account(account))).isEqualTo(PayoutAccountStatus.PENDING);
    }

    @Test
    void aListAnswerGivesTheFirstTransferOrNone() {
        assertThat(PayoutObjects.firstOfList(Map.of("object", "list", "data", List.of())).found()).isFalse();
        PayoutObjects.TransferLookup found = PayoutObjects.firstOfList(Map.of("object", "list", "data",
            List.of(transfer(t -> {}))));
        assertThat(found.found()).isTrue();
        assertThat(found.transfer().id()).isEqualTo("tr_1");
        assertThat(PayoutObjects.firstOfList(Map.of("object", "transfer"))).isNull();
    }

    @Test
    void everyReasonHasItsList() {
        assertThat(PayoutChecks.ACCOUNT_REASONS).doesNotHaveDuplicates();
        assertThat(PayoutChecks.TRANSFER_REASONS).doesNotHaveDuplicates();
        assertThat(PayoutChecks.transferGroup(TRANSFER)).isEqualTo("qb-" + TRANSFER);
    }

    @Test
    void ofTwoReportsInTheSameSecondTheMoreRestrictiveWins() {
        java.time.Instant t = java.time.Instant.parse("2026-10-01T00:00:00Z");
        // Newer always, older never.
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.RESTRICTED, t, PayoutAccountStatus.ENABLED,
            t.plusSeconds(1))).isTrue();
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.ENABLED, t, PayoutAccountStatus.RESTRICTED,
            t.minusSeconds(1))).isFalse();
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.PENDING, null, PayoutAccountStatus.ENABLED, t))
            .isTrue();
        // A tie never enables an account Stripe also held back in that second.
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.RESTRICTED, t, PayoutAccountStatus.ENABLED, t))
            .isFalse();
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.PENDING, t, PayoutAccountStatus.ENABLED, t))
            .isFalse();
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.ENABLED, t, PayoutAccountStatus.RESTRICTED, t))
            .isTrue();
        assertThat(PayoutAccountStatus.replaces(PayoutAccountStatus.ENABLED, t, PayoutAccountStatus.ENABLED, t))
            .isTrue();
    }
}
