package com.jabiz.runtime.security.protect;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SecurityPermissions;
import com.jabiz.security.ProtectedPermissions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The settings of the four eyes on protected grants (decision D48 item 1; docs/design/10-security.md section 16):
 * <ul>
 *   <li>{@code jabiz.security.protected-grants.enabled} (default true): off, nothing is protected;</li>
 *   <li>{@code .always} (default false): true, protected changes need approval from the start; false, they take
 *       effect directly during the setup window, until two administrators besides the first one exist;</li>
 *   <li>{@code .permissions} (comma-separated; default {@link ProtectedPermissions#DEFAULT}): the protected
 *       permissions; {@code *} and {@code security.protected-change.decide} always are (decision D51).</li>
 *   <li>{@code .trusted-email-domains} (comma-separated; default none): when set, an address of a user a protected
 *       change is for outside these domains (or their subdomains) is flagged in the approval's facts.</li>
 *   <li>{@code .administrator-permissions} (comma-separated; default {@link #DEFAULT_ADMINISTRATION}): who holds
 *       one of them (or {@code *}) through an enabled role counts as an administrator for the setup window and the
 *       first administrator's retirement, and an approver of a protected change needs one (decision D51).</li>
 * </ul>
 * Malformed permission codes are a startup error ({@code SECURITY}), as is an administrator permission that is not
 * protected (granting it would make an administrator without approval); a set leaving out both permissions that
 * change rights is a warning.
 */
@Component
public class ProtectedGrantSettings implements PlatformCheck {

    private final boolean enabled;
    private final boolean always;
    private final List<String> configured;
    private final ProtectedPermissions permissions;
    private final List<String> trustedDomains;
    private final List<String> configuredAdministration;
    private final Set<String> administration;

    /** The administrators' permissions by default: those that change rights (decision D51). */
    public static final List<String> DEFAULT_ADMINISTRATION = List.of(SecurityPermissions.USER_ROLE_WRITE,
        SecurityPermissions.ROLE_WRITE);

    public ProtectedGrantSettings(boolean enabled, boolean always, List<String> permissions,
        List<String> trustedDomains) {
        this(enabled, always, permissions, trustedDomains, List.of());
    }

    @Autowired
    public ProtectedGrantSettings(@Value("${jabiz.security.protected-grants.enabled:true}") boolean enabled,
        @Value("${jabiz.security.protected-grants.always:false}") boolean always,
        @Value("${jabiz.security.protected-grants.permissions:}") List<String> permissions,
        @Value("${jabiz.security.protected-grants.trusted-email-domains:}") List<String> trustedDomains,
        @Value("${jabiz.security.protected-grants.administrator-permissions:}") List<String> administration) {
        this.enabled = enabled;
        this.always = always;
        this.configured = permissions == null ? List.of() : List.copyOf(permissions);
        this.permissions = ProtectedPermissions.of(this.configured);
        this.trustedDomains = trustedDomains == null ? List.of() : trustedDomains.stream()
            .filter(domain -> domain != null && !domain.isBlank())
            .map(domain -> domain.strip().toLowerCase(java.util.Locale.ROOT)).toList();
        this.configuredAdministration = administration == null ? List.of() : administration.stream()
            .filter(code -> code != null && !code.isBlank()).map(String::strip).toList();
        Set<String> admin = new TreeSet<>(configuredAdministration.isEmpty() ? DEFAULT_ADMINISTRATION
            : configuredAdministration);
        admin.add(RequestContext.ALL_PERMISSIONS);
        this.administration = Set.copyOf(admin);
    }

    /**
     * The permissions that make an administrator (decision D51): {@code *} and the configured ones (by default
     * {@link #DEFAULT_ADMINISTRATION}).
     */
    public Set<String> administratorPermissions() {
        return administration;
    }

    /** Whether {@code email} is in one of the trusted domains (or none are configured, or there is no address). */
    public boolean trustedAddress(String email) {
        if (trustedDomains.isEmpty() || email == null || email.indexOf('@') < 0) {
            return true;
        }
        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase(java.util.Locale.ROOT);
        return trustedDomains.stream().anyMatch(trusted -> domain.equals(trusted) || domain.endsWith("." + trusted));
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
        List<CheckProblem> problems = new ArrayList<>();
        ProtectedPermissions.problems(configured).forEach(problem -> problems.add(
            CheckProblem.error("SECURITY", "jabiz.security.protected-grants.permissions", problem)));
        String where = "jabiz.security.protected-grants.administrator-permissions";
        List<String> wrong = ProtectedPermissions.problems(configuredAdministration);
        wrong.forEach(problem -> problems.add(CheckProblem.error("SECURITY", where, problem)));
        if (enabled && wrong.isEmpty()) {
            administration.stream().sorted().filter(code -> !permissions.isProtected(code)).forEach(code -> problems.add(
                CheckProblem.error("SECURITY", where, "'" + code + "' is not a protected permission: granting it"
                    + " would make an administrator without approval")));
            if (DEFAULT_ADMINISTRATION.stream().noneMatch(administration::contains)) {
                problems.add(CheckProblem.warning("SECURITY", where, "names neither " + String.join(" nor ",
                    DEFAULT_ADMINISTRATION) + ": their holders change rights but do not count as administrators,"
                    + " so the setup window may never end (the gap decision D51 closes)"));
            }
        }
        return List.copyOf(problems);
    }
}
