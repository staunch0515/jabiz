package com.jabiz.quizbuks.wallet;

import com.jabiz.quizbuks.country.CountryCatalog;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses to start with the development credit on next to a live Stripe key (docs/quizbuks/plans/Q5-answering.md,
 * D-Q5-8): with both, sponsors' wallets could be credited without money arriving in production.
 */
@Component
class DevCreditCheck implements PlatformCheck {

    private final boolean enabled;
    private final String stripeKey;

    DevCreditCheck(@Value("${" + DevCredit.ENABLED + ":false}") boolean enabled,
        @Value("${STRIPE_API_KEY:}") String stripeKey) {
        this.enabled = enabled;
        this.stripeKey = stripeKey;
    }

    @Override
    public List<CheckProblem> check() {
        return problems(enabled, stripeKey);
    }

    static List<CheckProblem> problems(boolean enabled, String stripeKey) {
        if (enabled && stripeKey != null && stripeKey.startsWith("sk_live_")) {
            return List.of(CheckProblem.error(CountryCatalog.CATEGORY, DevCredit.ENABLED, "The development credit "
                + "(QB_WALLET_DEV_CREDIT) is on next to a live Stripe key: turn it off in production"));
        }
        return List.of();
    }
}
