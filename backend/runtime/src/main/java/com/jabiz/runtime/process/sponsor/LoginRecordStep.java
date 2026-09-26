package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.security.LoginAttemptPolicy;
import org.springframework.stereotype.Component;

/**
 * Registers the login record of an attempt on a known user, with the failure counter and lock that follow from it
 * ({@link LoginAttemptPolicy}). Names of no user leave no record: they have no account to lock.
 */
@Component
public class LoginRecordStep implements ComputeStep<NoMetadata, LoginContext> {

    private final LoginAttemptPolicy policy;

    public LoginRecordStep(LoginAttemptPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void compute(NoMetadata metadata, LoginContext ctx) {
        if (ctx.outcome() == null) {
            return;
        }
        EntityInstance user = ctx.user().orElseThrow();
        LoginAttemptPolicy.State next = policy.next(ctx.latestState(), ctx.outcome(), ctx.opTime());
        ctx.setLoginRecordId(ctx.changes().insert(SecurityEntities.LOGIN_RECORD,
            SponsorSignInProcess.record(user.id(), ctx.userName(), ctx.outcome(), next, ctx.opTime(),
                ctx.request().requestId())));
    }
}
