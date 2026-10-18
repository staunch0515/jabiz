package com.jabiz.runtime.security.protect;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.security.SignInEntries;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Startup warning (category {@code SECURITY}; decision D48, review L2): a registration role of an entry - granted by
 * self-registration and by OIDC auto-provisioning (10 sections 12 and 15.7) - that holds a protected permission. Once
 * the setup window has ended such a sign-up is refused (it would be a protected grant nobody approved); before, it
 * gives anyone who registers a protected permission. Roles are data, so this reads them; blocks on the reactive client
 * on the startup thread, as the other schema checks do.
 */
@Component
class ProtectedGrantChecks implements PlatformCheck {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ProtectedGrantSettings settings;
    private final SignInEntries entries;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    ProtectedGrantChecks(ProtectedGrantSettings settings, SignInEntries entries, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.settings = settings;
        this.entries = entries;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public List<CheckProblem> check() {
        if (!settings.enabled()) {
            return List.of();
        }
        Set<String> codes = new TreeSet<>();
        entries.all().forEach(entry -> codes.addAll(entry.registrationRoles()));
        if (codes.isEmpty()) {
            return List.of();
        }
        String sql = "SELECT r.role_code, rp.permission FROM (SELECT DISTINCT ON (role_id) role_id, role_code,"
            + " is_deleted FROM sec_role_version WHERE role_code = ANY(:codes) ORDER BY role_id, version_no DESC) r"
            + " JOIN (SELECT DISTINCT ON (role_permission_id) role_id, permission, is_deleted FROM"
            + " sec_role_permission_version ORDER BY role_permission_id, version_no DESC) rp ON rp.role_id = r.role_id"
            + " WHERE NOT r.is_deleted AND NOT rp.is_deleted ORDER BY r.role_code, rp.permission";
        List<Map<String, Object>> rows = storages.getEngine(poolRef)
            .select(sql, Map.of("codes", BoundValue.of(codes.toArray(String[]::new)))).collectList().block(TIMEOUT);
        List<CheckProblem> problems = new ArrayList<>();
        for (Map<String, Object> row : rows == null ? List.<Map<String, Object>>of() : rows) {
            String permission = Rows.string(row.get("permission"));
            if (settings.permissions().isProtected(permission)) {
                problems.add(CheckProblem.warning("SECURITY", "Role " + Rows.string(row.get("role_code")),
                    "is a registration role and holds protected permission " + permission
                        + ": registering gives it to anyone, and is refused once the setup window has ended"));
            }
        }
        return List.copyOf(problems);
    }
}
