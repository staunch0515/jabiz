package com.jabiz.quizbuks.shopify;

import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Ends the sessions of a shop that uninstalled the app (docs/quizbuks/shopify/S1-install.md section 5): a session of a
 * member is not renewed while the shop's status on the membership is {@code UNINSTALLED}. A new sign-in with a session
 * token is let through: Shopify issues those to installed apps only, so it means the shop installed the app again, and
 * {@code QBS_SHOP_JOIN} makes the shop active once more.
 */
@Component
class ShopSignInGuard implements SignInGuard {

    static final String MEMBERSHIP = "membership";

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(MEMBERSHIP, QbsShops.defaultDataset(QbsShops.MEMBER), "userId"));
    }

    @Override
    public SignInDecision check(SignInAttempt attempt) {
        boolean uninstalled = attempt.rows(MEMBERSHIP).stream()
            .anyMatch(row -> QbsShops.UNINSTALLED.equals(row.get("shopStatus")));
        return attempt.factor() == SignInAttempt.Factor.REFRESH && uninstalled
            ? SignInDecision.refuse("shop uninstalled") : SignInDecision.ALLOW;
    }
}
