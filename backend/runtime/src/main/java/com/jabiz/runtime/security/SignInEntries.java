package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The sign-in entries (docs/design/10-security.md section 15; decision D36 item 1): which roles each front end accepts,
 * whether it lets people register and what they are granted, whether it requires a verified e-mail address, and where
 * its pages live. Configured under {@code jabiz.security.entries.<name>}.
 *
 * <p>The entry {@value RequestContext#DEFAULT_ENTRY} always exists: unconfigured it accepts every role, as before
 * entries existed; configured, as configured. A request without an entry is for it, so the administration needs no
 * change. The startup check reports every problem of every entry at once; only entries without errors are offered.
 */
@Component
public class SignInEntries implements PlatformCheck {

    static final String PROPERTY = "jabiz.security.entries";

    /** Role code that stands for every role. */
    public static final String ALL_ROLES = "*";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern APP_PATH = Pattern.compile("/([A-Za-z0-9._~-]+/?)*");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    /**
     * One entry.
     *
     * @param acceptedRoles        role codes whose holders may sign in here; {@link #ALL_ROLES} for every role
     * @param selfRegistration     whether people may register themselves here (phase 16b-2)
     * @param registrationRoles    the roles a registration grants
     * @param requireVerifiedEmail whether only users with a verified e-mail address may sign in here
     * @param appPath              where the entry's pages are, for links in mail
     */
    public record Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
        boolean requireVerifiedEmail, String appPath) {

        public Entry {
            acceptedRoles = Set.copyOf(acceptedRoles);
            registrationRoles = Set.copyOf(registrationRoles);
        }

        /** Whether holders of the role may sign in through this entry. */
        public boolean accepts(String roleCode) {
            return acceptedRoles.contains(ALL_ROLES) || acceptedRoles.contains(roleCode);
        }
    }

    /** The administration entry when none is configured: every role, as before entries existed. */
    static final Entry DEFAULT = new Entry(RequestContext.DEFAULT_ENTRY, Set.of(ALL_ROLES), false, Set.of(), false,
        "/");

    /** An entry as configured; every item optional. */
    public record Config(List<String> acceptedRoles, Boolean selfRegistration, List<String> registrationRoles,
        Boolean requireVerifiedEmail, String appPath) {}

    private final Map<String, Config> configured;
    private final boolean mailEnabled;
    private final Supplier<Collection<String>> roleCodes;
    private final Map<String, Entry> usable = new LinkedHashMap<>();

    @Autowired
    public SignInEntries(Environment environment, StorageAdapterRegistry storages) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.mapOf(String.class, Config.class)).orElse(Map.of()),
            environment.getProperty("jabiz.mail.enabled", Boolean.class, false),
            () -> currentRoleCodes(storages, environment.getProperty("jabiz.storage.default-pool-ref", "default")));
    }

    /**
     * @param roleCodes the role codes that exist, read by the check only (null: not checked)
     */
    SignInEntries(Map<String, Config> configured, boolean mailEnabled, Supplier<Collection<String>> roleCodes) {
        this.configured = new TreeMap<>(configured);
        this.mailEnabled = mailEnabled;
        this.roleCodes = roleCodes;
        if (!this.configured.containsKey(RequestContext.DEFAULT_ENTRY)) {
            usable.put(DEFAULT.name(), DEFAULT);
        }
        this.configured.forEach((name, config) -> {
            if (errors(name, config).isEmpty()) {
                usable.put(name, entry(name, config));
            }
        });
    }

    /** The usable entry of that name; the default entry for null. */
    public Optional<Entry> find(String name) {
        return Optional.ofNullable(usable.get(name == null ? RequestContext.DEFAULT_ENTRY : name));
    }

    /** The entry of that name, or one accepting nobody: an entry that went away refuses (default deny). */
    public Entry resolve(String name) {
        String key = name == null ? RequestContext.DEFAULT_ENTRY : name;
        return find(key).orElseGet(() -> new Entry(key, Set.of(), false, Set.of(), true, "/"));
    }

    public List<Entry> all() {
        return List.copyOf(usable.values());
    }

    @Override
    public List<CheckProblem> check() {
        List<CheckProblem> problems = new ArrayList<>();
        Set<String> referenced = new TreeSet<>();
        configured.forEach((name, config) -> {
            String location = PROPERTY + "." + name;
            errors(name, config).forEach(error -> problems.add(CheckProblem.error(SessionChecks.CATEGORY, location,
                error)));
            Entry entry = entry(name, config);
            if (entry.selfRegistration() && !mailEnabled) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, location, "self-registration is on, but "
                    + "jabiz.mail.enabled is not: registered users cannot verify their address"));
            }
            entry.acceptedRoles().stream().filter(role -> !ALL_ROLES.equals(role)).forEach(referenced::add);
            referenced.addAll(entry.registrationRoles());
        });
        if (!referenced.isEmpty() && roleCodes != null) {
            try {
                Set<String> existing = new java.util.HashSet<>(roleCodes.get());
                referenced.stream().filter(role -> !existing.contains(role)).forEach(role -> problems.add(
                    CheckProblem.warning(SessionChecks.CATEGORY, PROPERTY, "role " + role + " does not exist (yet)")));
            } catch (RuntimeException e) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, PROPERTY,
                    "the role codes could not be checked: " + e.getMessage()));
            }
        }
        return problems;
    }

    private static List<String> errors(String name, Config config) {
        List<String> errors = new ArrayList<>();
        if (name == null || !NAME.matcher(name).matches()) {
            errors.add("the name must be a lower-case letter followed by up to 39 lower-case letters, digits or "
                + "hyphens");
        }
        Entry entry = entry(name, config);
        if (entry.acceptedRoles().isEmpty()) {
            errors.add("accepted-roles is empty: nobody could sign in");
        }
        for (String role : union(entry.acceptedRoles(), entry.registrationRoles())) {
            if (!ALL_ROLES.equals(role) && !ROLE_CODE.matcher(role).matches()) {
                errors.add("'" + role + "' is not a role code");
            }
        }
        if (entry.registrationRoles().contains(ALL_ROLES)) {
            errors.add("registration-roles cannot be " + ALL_ROLES);
        }
        entry.registrationRoles().stream().filter(role -> !ALL_ROLES.equals(role) && !entry.accepts(role)).sorted()
            .forEach(role -> errors.add("registration role " + role + " is not among accepted-roles: registered "
                + "users could not sign in here"));
        if (entry.selfRegistration() && entry.registrationRoles().isEmpty()) {
            errors.add("self-registration is on, but registration-roles is empty");
        }
        if (entry.appPath() == null || !APP_PATH.matcher(entry.appPath()).matches()
            || List.of(entry.appPath().split("/")).stream().anyMatch(s -> s.equals(".") || s.equals(".."))) {
            errors.add("app-path must be an absolute path such as /portal/ (letters, digits, . _ ~ -), not '"
                + entry.appPath() + "'");
        }
        return errors;
    }

    private static Entry entry(String name, Config config) {
        Config c = config == null ? new Config(null, null, null, null, null) : config;
        return new Entry(name, trimmed(c.acceptedRoles()), Boolean.TRUE.equals(c.selfRegistration()),
            trimmed(c.registrationRoles()), Boolean.TRUE.equals(c.requireVerifiedEmail()),
            c.appPath() == null ? "/" : c.appPath().trim());
    }

    private static Set<String> trimmed(List<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values != null) {
            values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).forEach(result::add);
        }
        return result;
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> all = new TreeSet<>(a);
        all.addAll(b);
        return all;
    }

    /** The role codes of the roles in their current version (startup only: blocks). */
    private static Collection<String> currentRoleCodes(StorageAdapterRegistry storages, String poolRef) {
        return storages.getEngine(poolRef).select("""
                SELECT role_code FROM (SELECT DISTINCT ON (role_id) role_code, is_deleted FROM sec_role_version
                    ORDER BY role_id, effect_start_time DESC, version_no DESC) latest WHERE NOT is_deleted""",
                Map.of())
            .map(row -> String.valueOf(row.get("role_code")))
            .collectList()
            .block(Duration.ofSeconds(30));
    }
}
