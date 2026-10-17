package com.jabiz.runtime.security.protect;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.security.ProtectedPermissions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The settings of the four eyes on protected grants (decision D48 item 1; docs/design/10-security.md section 16):
 * <ul>
 *   <li>{@code jabiz.security.protected-grants.enabled} (default true): off, nothing is protected;</li>
 *   <li>{@code .always} (default false): true, protected changes need approval from the start; false, they take
 *       effect directly during the setup window, until two administrators besides the first one exist;</li>
 *   <li>{@code .permissions} (comma-separated; default {@link ProtectedPermissions#DEFAULT}): the protected
 *       permissions; {@code *} always is one.</li>
 * </ul>
 * Malformed permission codes are a startup error ({@code SECURITY}).
 */
@Component
public class ProtectedGrantSettings implements PlatformCheck {

    private final boolean enabled;
    private final boolean always;
    private final List<String> configured;
    private final ProtectedPermissions permissions;

    public ProtectedGrantSettings(@Value("${jabiz.security.protected-grants.enabled:true}") boolean enabled,
        @Value("${jabiz.security.protected-grants.always:false}") boolean always,
        @Value("${jabiz.security.protected-grants.permissions:}") List<String> permissions) {
        this.enabled = enabled;
        this.always = always;
        this.configured = permissions == null ? List.of() : List.copyOf(permissions);
        this.permissions = ProtectedPermissions.of(this.configured);
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean always() {
        return always;
    }

    public ProtectedPermissions permissions() {
        return permissions;
    }

    @Override
    public List<CheckProblem> check() {
        return ProtectedPermissions.problems(configured).stream()
            .map(problem -> CheckProblem.error("SECURITY", "jabiz.security.protected-grants.permissions", problem))
            .toList();
    }
}
