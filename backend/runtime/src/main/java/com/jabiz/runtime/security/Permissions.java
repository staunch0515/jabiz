package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.PermissionDeniedException;

import java.util.Collection;

/**
 * Request-time permission checks of the entry points (docs/design/10-security.md section 5). Default deny: what
 * declares no permission is refused, except in the {@code dev} profile where the startup checks only warn about it.
 */
public final class Permissions {

    private Permissions() {}

    /** The caller must hold {@code permission}. */
    public static void require(RequestContext context, String permission, String what) {
        if (!context.hasPermission(permission)) {
            throw new PermissionDeniedException(permission, what + " requires permission " + permission);
        }
    }

    /**
     * The caller must hold the declared permission; an undeclared (null) permission is refused unless
     * {@code development}.
     */
    public static void requireDeclared(RequestContext context, String permission, boolean development, String what) {
        if (permission == null) {
            if (!development) {
                throw new PermissionDeniedException("-", what + " declares no permission");
            }
            return;
        }
        require(context, permission, what);
    }

    /** The caller must hold every declared permission; none declared is refused unless {@code development}. */
    public static void requireAll(RequestContext context, Collection<String> permissions, boolean development,
        String what) {
        if (permissions.isEmpty()) {
            if (!development) {
                throw new PermissionDeniedException("-", what + " declares no permissions");
            }
            return;
        }
        for (String permission : permissions.stream().sorted().toList()) {
            require(context, permission, what);
        }
    }
}
