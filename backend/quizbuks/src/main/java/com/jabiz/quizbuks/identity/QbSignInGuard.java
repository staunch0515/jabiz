package com.jabiz.quizbuks.identity;

import com.jabiz.quizbuks.operator.OperatorRules;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInDecision;
import com.jabiz.security.SignInGuard;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * QuizBuks's check before a session (docs/design/10-security.md section 15.3; D-Q2-4): a banned user does not get in
 * at any entry, at sign-in and at the next refresh of a session they hold. The platform reads the user's
 * {@code QbUserBan} beforehand; the rule is {@link BanRules}. At the operators' entry (Q48a) the user must also be a
 * current member of an operator that runs ({@link OperatorRules#admits}): the role says what a member may do, the
 * membership that they still act for an operator.
 */
@Component
class QbSignInGuard implements SignInGuard {

    static final String BAN = "ban";
    static final String MEMBERSHIP = "operatorMembership";

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(BAN, QbAccounts.defaultDataset(QbAccounts.BAN), "userId"),
            SignInLoad.of(MEMBERSHIP, QbOperators.defaultDataset(QbOperators.MEMBER), "userId"));
    }

    @Override
    public SignInDecision check(SignInAttempt attempt) {
        if (BanRules.refuses(attempt.rows(BAN))) {
            return SignInDecision.refuse("banned");
        }
        if (QbOperators.ENTRY.equals(attempt.entry()) && !OperatorRules.admits(attempt.rows(MEMBERSHIP))) {
            return SignInDecision.refuse("operator-membership");
        }
        return SignInDecision.ALLOW;
    }
}
