package com.jabiz.quizbuks.identity;

import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QuizBuks's check before a session (docs/design/10-security.md section 15.3; D-Q2-4): a banned user does not get in
 * at any entry, at sign-in and at the next refresh of a session they hold. The platform reads the user's
 * {@code QbUserBan} beforehand; the rule is {@link BanRules}.
 */
@Component
class QbSignInGuard implements SignInGuard {

    static final String BAN = "ban";

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(BAN, QbAccounts.defaultDataset(QbAccounts.BAN), "userId"));
    }

    @Override
    public SignInDecision check(SignInAttempt attempt) {
        return BanRules.refuses(attempt.rows(BAN)) ? SignInDecision.refuse("banned") : SignInDecision.ALLOW;
    }
}
