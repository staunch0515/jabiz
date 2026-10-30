package com.jabiz.quizbuks.operator;

import com.jabiz.security.EntryScopeResolver;
import com.jabiz.security.SignInAttempt;
import com.jabiz.security.SignInLoad;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The operators' entry scope (Q59c-2, platform decision D64): a member's session works on the operator they are a
 * current member of, while it runs; worked out at sign-in and at every refresh, so that a member removed, or an
 * operator suspended, has no session left after the next refresh (the sign-in guard refuses them too).
 */
@Component
class OperatorScope implements EntryScopeResolver {

    static final String MEMBERSHIPS = "operatorMemberships";

    @Override
    public String entry() {
        return QbOperators.ENTRY;
    }

    @Override
    public String scope() {
        return QbOperators.SCOPE;
    }

    @Override
    public List<SignInLoad> loads() {
        return List.of(SignInLoad.of(MEMBERSHIPS, QbOperators.defaultDataset(QbOperators.MEMBER), "userId"));
    }

    @Override
    public String resolve(SignInAttempt attempt) {
        // A person is a current member of one operator at a time (D-Q48); none, or one that does not run: no session.
        return attempt.rows(MEMBERSHIPS).stream()
            .filter(row -> row.get("removedAt") == null && Boolean.TRUE.equals(row.get("operatorActive")))
            .map(row -> String.valueOf(row.get("operatorId")))
            .findFirst().orElse(null);
    }
}
