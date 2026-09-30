package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.security.LoginAttemptPolicy;
import org.springframework.stereotype.Component;

/** {@link LoginRecordStep} for the second step of a sign-in and step-ups. */
@Component
public class MfaLoginRecordStep implements ComputeStep<NoMetadata, MfaContext> {

    private final LoginAttemptPolicy policy;

    public MfaLoginRecordStep(LoginAttemptPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void compute(NoMetadata metadata, MfaContext ctx) {
        LoginRecordStep.register(policy, ctx);
    }
}
