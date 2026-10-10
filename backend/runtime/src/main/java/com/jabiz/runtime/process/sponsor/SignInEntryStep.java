package com.jabiz.runtime.process.sponsor;

import com.jabiz.process.ComputeStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.security.SignInEntries;
import org.springframework.stereotype.Component;

/**
 * Resolves the sign-in entry an attempt is for (docs/design/10-security.md section 15; decision D36): which roles
 * count, and whether a verified address is required. An entry that is not (or no longer) configured accepts nobody.
 */
@Component
public class SignInEntryStep<C extends LoginContext> implements ComputeStep<NoMetadata, C> {

    private final SignInEntries entries;

    public SignInEntryStep(SignInEntries entries) {
        this.entries = entries;
    }

    public static <C extends LoginContext> StepSpec<NoMetadata, C> spec() {
        return StepSpec.of(SignInEntryStep.class, NoMetadata.INSTANCE);
    }

    @Override
    public void compute(NoMetadata metadata, C ctx) {
        ctx.setEntry(entries.resolve(ctx.source().entryOrDefault()));
    }
}
