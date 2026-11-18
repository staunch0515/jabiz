package com.jabiz.runtime.firstlaunch.accounts;

/**
 * The first launch's waiting role (docs/design/09-decisions.md D82; plan F-LAUNCH section 5.2). An account the first
 * launch creates holds it until one of its configured roles is in effect: it gives no permission, but lets the user
 * sign in at the administration's entry, where the role's {@code requireMfa} makes them bind a second factor - which
 * the grants wait for. Only the first launch gives, enables or changes it ({@link WaitingRoleWriteCheck}); it never
 * holds a permission; the run's end disables it for good.
 */
public final class WaitingRole {

    /** The role's code. The entry the invitations lead to must accept it (checked at startup). */
    public static final String CODE = "FIRST_LAUNCH_PENDING";

    private WaitingRole() {}
}
