package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.security.LoginAttemptPolicy;
import org.springframework.stereotype.Component;

/** {@link LoginRecordStep} for sign-ins through an OpenID Connect provider. */
@Component
public class OidcLoginRecordStep implements ComputeStep<NoMetadata, OidcContext> {

    private final LoginAttemptPolicy policy;

    public OidcLoginRecordStep(LoginAttemptPolicy policy) {
        this.policy = policy;
    }

    @Override
    public void compute(NoMetadata metadata, OidcContext ctx) {
        LoginRecordStep.register(policy, ctx);
    }
}
