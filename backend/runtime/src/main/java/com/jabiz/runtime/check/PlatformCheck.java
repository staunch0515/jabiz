package com.jabiz.runtime.check;

import java.util.List;

/**
 * One startup self-check (docs/design/07-quality.md section 1). The same checks run when the application starts and
 * in the {@code platformCheck} build task; {@link PlatformCheckRunner} runs all of them and reports every problem
 * at once. Checks may block: they run on the startup thread, never on the request path.
 */
public interface PlatformCheck {

    /** Every problem found; empty when the check passes. */
    List<CheckProblem> check();
}
