package com.jabiz.quizbuks.setup;

/**
 * The first launch's roles (F-LAUNCH, docs/quizbuks/plans/impl/F-LAUNCH.md section 5.2). The platform's first launch
 * gives every account it creates the waiting role {@link #PENDING}: no permissions, a second factor required, so the
 * account can sign in to the administration and set up its second factor before its own roles are granted. The
 * administration's entry therefore accepts it ({@code QB_ADMIN_ROLES}, {@code application.yml}).
 */
public final class FirstLaunchRoles {

    /** The waiting role, created by the platform's first launch; its name is fixed here until the platform owns it. */
    public static final String PENDING = "FIRST_LAUNCH_PENDING";

    private FirstLaunchRoles() {}
}
