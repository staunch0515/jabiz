package com.jabiz.quizbuks.wallet;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StripeModeCheckTest {

    static StripeSettings settings(boolean livemode) {
        return new StripeSettings(livemode, "2025-03-31.basil", Duration.ofHours(1), null,
            "https://quizbuks.example.com/sponsor/");
    }

    static List<String> errors(boolean livemode, boolean devCredit, String key) {
        return StripeModeCheck.problems(settings(livemode), devCredit, key).stream()
            .filter(p -> p.severity() == CheckProblem.Severity.ERROR).map(CheckProblem::toString).toList();
    }

    @Test
    void theKeyMustBeOfTheConfiguredMode() {
        assertThat(errors(false, false, "sk_test_abc")).isEmpty();
        assertThat(errors(false, false, "rk_test_abc")).isEmpty();
        assertThat(errors(false, false, "sk_live_abc")).singleElement().asString().contains("STRIPE_API_KEY");
        assertThat(errors(false, false, "rk_live_abc")).hasSize(1);
        assertThat(errors(true, false, "sk_live_abc")).isEmpty();
        assertThat(errors(true, false, "rk_live_abc")).isEmpty();
        assertThat(errors(true, false, "sk_test_abc")).hasSize(1);
        assertThat(errors(true, false, "rk_test_abc")).hasSize(1);
        // A publishable key is not an API key in either mode.
        assertThat(errors(false, false, "pk_test_abc")).hasSize(1);
        // A missing key is the platform's check EXTERNAL's to report.
        assertThat(errors(false, false, "")).isEmpty();
        assertThat(errors(true, false, null)).isEmpty();
    }

    @Test
    void theDevelopmentCreditNeverMeetsLiveMode() {
        assertThat(errors(false, true, "sk_test_abc")).isEmpty();
        assertThat(errors(false, true, "")).isEmpty();
        assertThat(errors(false, true, "sk_live_abc")).anySatisfy(p -> assertThat(p)
            .contains(DevCredit.ENABLED)).hasSize(2);
        assertThat(errors(false, true, "rk_live_abc")).anySatisfy(p -> assertThat(p).contains(DevCredit.ENABLED));
        assertThat(errors(true, true, "sk_live_abc")).singleElement().asString().contains(DevCredit.ENABLED);
        assertThat(errors(true, true, "")).singleElement().asString().contains(DevCredit.ENABLED);
        assertThat(errors(true, false, "")).isEmpty();
    }

    @Test
    void liveModeTakesStripesCheckoutOnly() {
        StripeSettings stub = new StripeSettings(true, "2025-03-31.basil", Duration.ofHours(1),
            "http://localhost:8093/", "https://q.example.com/sponsor/");
        assertThat(StripeModeCheck.problems(stub, false, "sk_live_x")).anySatisfy(p -> assertThat(p.toString())
            .contains("checkout-url-prefix"));
        StripeSettings badVersion = new StripeSettings(false, "latest", Duration.ofHours(1), null, "x");
        assertThat(StripeModeCheck.problems(badVersion, false, "")).anySatisfy(p -> assertThat(p.toString())
            .contains("api-version"));
    }

    @Test
    void withoutTheSponsorConsoleItOnlyWarns() {
        StripeSettings none = new StripeSettings(false, "2025-03-31.basil", Duration.ofHours(1), null, "");
        assertThat(StripeModeCheck.problems(none, false, "sk_test_x")).singleElement()
            .satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.WARNING));
    }

    @Test
    void lockNamesKeepTheKindsApart() {
        assertThat(QbWallets.lockName(QbWallets.SPONSOR, "u1")).isEqualTo("qb.wallet:SPONSOR:u1");
        assertThat(QbWallets.lockName(QbWallets.TAKER, "u1")).isNotEqualTo(QbWallets.lockName(QbWallets.SPONSOR, "u1"));
        assertThat(QbWallets.lockName(QbWallets.SPONSOR, null)).isNull();
    }
}
