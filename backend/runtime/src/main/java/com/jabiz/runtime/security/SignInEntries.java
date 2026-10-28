package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.fasterxml.jackson.annotation.JsonValue;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.check.PlatformCheck;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.web.JabizWebProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
 * change. Each entry may set how long its sessions live, whether they end when idle and where the front end keeps
 * them ({@code .session.*}, decision D43 item 2; the global settings otherwise). The startup check reports every
 * problem of every entry at once; only entries without errors are offered.
 */
@Component
public class SignInEntries implements PlatformCheck {

    static final String PROPERTY = "jabiz.security.entries";

    /** Role code that stands for every role. */
    public static final String ALL_ROLES = "*";

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");
    private static final Pattern APP_PATH = Pattern.compile("/([A-Za-z0-9._~-]+/?)*");
    private static final Pattern ROLE_CODE = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    /** Where a front end keeps the refresh token of an entry's session (decision D43 item 2). */
    public enum Persistence {
        /** In the tab ({@code sessionStorage}): closing it ends the session. The default. */
        TAB,
        /** On the device ({@code localStorage}): the session outlives closing the app. Never the administration's. */
        DEVICE;

        /** As configured and as {@code GET /api/auth/entries/{name}} tells it: {@code tab} or {@code device}. */
        @JsonValue
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The persistence named {@code value} (any case, surrounding blanks ignored); empty for an unknown one. */
        static Optional<Persistence> of(String value) {
            String wanted = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
            for (Persistence persistence : values()) {
                if (persistence.value().equals(wanted)) {
                    return Optional.of(persistence);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The sessions of an entry (decision D43 item 2), with the global settings filled in.
     *
     * @param refreshTokenTtl how long a refresh token lives; every refresh starts it afresh (sliding)
     * @param idleTimeout     how long after its access token expires a session can still be refreshed;
     *                        {@link Duration#ZERO} for no idle end (only the lifetime ends it)
     * @param persistence     where the front end keeps the refresh token
     */
    public record Session(Duration refreshTokenTtl, Duration idleTimeout, Persistence persistence) {

        /** The platform's defaults ({@code jabiz.security.refresh-token-ttl}, {@code .session.idle-timeout}). */
        public static final Session DEFAULT = new Session(Duration.ofHours(8), Duration.ofMinutes(15),
            Persistence.TAB);

        public Session {
            Objects.requireNonNull(refreshTokenTtl, "refreshTokenTtl must not be null");
            Objects.requireNonNull(idleTimeout, "idleTimeout must not be null");
            Objects.requireNonNull(persistence, "persistence must not be null");
        }

        /** Whether an idle session ends at all. */
        public boolean idleEnds() {
            return idleTimeout.isPositive();
        }

        /**
         * Whether sessions here are long: kept on the device, or never ended for idleness (decision D43 item 2). Such
         * an entry never accepts every role, and roles granting the platform's administration do not count in its
         * sessions ({@link Rbac#withoutAdministration}).
         */
        public boolean longLived() {
            return persistence == Persistence.DEVICE || !idleEnds();
        }
    }

    /**
     * One entry.
     *
     * @param acceptedRoles        role codes whose holders may sign in here; {@link #ALL_ROLES} for every role
     * @param selfRegistration     whether people may register themselves here (phase 16b-2)
     * @param registrationRoles    the roles a registration grants
     * @param requireVerifiedEmail whether only users with a verified e-mail address may sign in here
     * @param appPath              the root of the entry's frontend, which mailed links lead below (decision D43 item 4)
     * @param session              its sessions: lifetime, idle end and persistence (phase 16m)
     * @param externalToken        the external token provider people sign in with here (decision D59); null: none
     * @param accountDeletion      whether users of this entry may ask for the deletion of their account here, signed
     *                             in or from a mailed link (section 17.5; decision D62)
     */
    public record Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
        boolean requireVerifiedEmail, String appPath, Session session, boolean emailLink, String externalToken,
        boolean accountDeletion) {

        public Entry {
            acceptedRoles = Set.copyOf(acceptedRoles);
            registrationRoles = Set.copyOf(registrationRoles);
            Objects.requireNonNull(session, "session must not be null");
            externalToken = externalToken == null || externalToken.isBlank() ? null : externalToken.trim();
        }

        /** An entry where nobody asks for the deletion of their account. */
        public Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
            boolean requireVerifiedEmail, String appPath, Session session, boolean emailLink, String externalToken) {
            this(name, acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath, session,
                emailLink, externalToken, false);
        }

        /** An entry without sign-in by an external token (decision D59). */
        public Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
            boolean requireVerifiedEmail, String appPath, Session session, boolean emailLink) {
            this(name, acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath, session,
                emailLink, null);
        }

        /** An entry without sign-in by e-mail link (decision D54). */
        public Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
            boolean requireVerifiedEmail, String appPath, Session session) {
            this(name, acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath, session,
                false);
        }

        /** An entry with the platform's default sessions. */
        public Entry(String name, Set<String> acceptedRoles, boolean selfRegistration, Set<String> registrationRoles,
            boolean requireVerifiedEmail, String appPath) {
            this(name, acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath,
                Session.DEFAULT);
        }

        /** Whether holders of the role may sign in through this entry. */
        public boolean accepts(String roleCode) {
            return acceptedRoles.contains(ALL_ROLES) || acceptedRoles.contains(roleCode);
        }
    }

    /** Longest refresh token lifetime an entry may set (decision D43 item 2). */
    public static final Duration MAX_REFRESH_TOKEN_TTL = Duration.ofDays(90);

    /** An entry as configured; every item optional. */
    public record Config(List<String> acceptedRoles, Boolean selfRegistration, List<String> registrationRoles,
        Boolean requireVerifiedEmail, String appPath, SessionConfig session, Boolean emailLink, String externalToken,
        Boolean accountDeletion) {

        /** Binding takes this one (a second constructor would leave the binder without a choice). */
        @ConstructorBinding
        public Config {
        }

        public Config(List<String> acceptedRoles, Boolean selfRegistration, List<String> registrationRoles,
            Boolean requireVerifiedEmail, String appPath, SessionConfig session, Boolean emailLink,
            String externalToken) {
            this(acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath, session, emailLink,
                externalToken, null);
        }

        public Config(List<String> acceptedRoles, Boolean selfRegistration, List<String> registrationRoles,
            Boolean requireVerifiedEmail, String appPath, SessionConfig session, Boolean emailLink) {
            this(acceptedRoles, selfRegistration, registrationRoles, requireVerifiedEmail, appPath, session, emailLink,
                null);
        }
    }

    /**
     * The sessions of an entry as configured ({@code jabiz.security.entries.<name>.session.*}); each item optional,
     * the global setting otherwise.
     *
     * @param idleTimeout {@code 0} for no idle end
     * @param persistence {@code tab} (the default) or {@code device}
     */
    public record SessionConfig(Duration refreshTokenTtl, Duration idleTimeout, String persistence) {}

    private final Map<String, Config> configured;
    private final boolean mailEnabled;
    private final Supplier<Collection<String>> roleCodes;
    private final Session defaults;
    private final Map<String, Entry> usable = new LinkedHashMap<>();
    /** The SPAs served, to check the entries' app-paths against; null: not checked. */
    private volatile JabizWebProperties web;

    @Autowired
    public SignInEntries(Environment environment, StorageAdapterRegistry storages) {
        this(Binder.get(environment).bind(PROPERTY, Bindable.mapOf(String.class, Config.class)).orElse(Map.of()),
            environment.getProperty("jabiz.mail.enabled", Boolean.class, false),
            () -> currentRoleCodes(storages, environment.getProperty("jabiz.storage.default-pool-ref", "default")),
            new Session(Binder.get(environment).bind("jabiz.security.refresh-token-ttl", Duration.class)
                .orElse(Session.DEFAULT.refreshTokenTtl()), Binder.get(environment)
                .bind("jabiz.security.session.idle-timeout", Duration.class).orElse(Session.DEFAULT.idleTimeout()),
                Persistence.TAB));
    }

    /**
     * With the platform's default sessions.
     *
     * @param roleCodes the role codes that exist, read by the check only (null: not checked)
     */
    SignInEntries(Map<String, Config> configured, boolean mailEnabled, Supplier<Collection<String>> roleCodes) {
        this(configured, mailEnabled, roleCodes, Session.DEFAULT);
    }

    /**
     * @param roleCodes the role codes that exist, read by the check only (null: not checked)
     * @param defaults  the global session settings, for entries (and items) that set none
     */
    SignInEntries(Map<String, Config> configured, boolean mailEnabled, Supplier<Collection<String>> roleCodes,
        Session defaults) {
        this.configured = new TreeMap<>(configured);
        this.mailEnabled = mailEnabled;
        this.roleCodes = roleCodes;
        this.defaults = Objects.requireNonNull(defaults, "defaults must not be null");
        if (!this.configured.containsKey(RequestContext.DEFAULT_ENTRY)) {
            // Unconfigured, the administration accepts every role, as before entries existed.
            usable.put(RequestContext.DEFAULT_ENTRY, new Entry(RequestContext.DEFAULT_ENTRY, Set.of(ALL_ROLES), false,
                Set.of(), false, "/", defaults));
        }
        this.configured.forEach((name, config) -> {
            if (errors(name, config).isEmpty()) {
                usable.put(name, entry(name, config));
            }
        });
    }

    /** The usable entry of that name; the default entry for none (null or blank, as everywhere entries are named). */
    public Optional<Entry> find(String name) {
        return Optional.ofNullable(usable.get(key(name)));
    }

    /** The entry of that name, or one accepting nobody: an entry that went away refuses (default deny). */
    public Entry resolve(String name) {
        String key = key(name);
        return find(key).orElseGet(() -> new Entry(key, Set.of(), false, Set.of(), true, "/", defaults));
    }

    /**
     * The sessions of the entry of that name (none: the administration's); the global settings for an entry that went
     * away, whose sessions cannot be renewed anyway.
     */
    public Session session(String name) {
        return resolve(name).session();
    }

    /** The global session settings, which entries that set none use. */
    public Session defaults() {
        return defaults;
    }

    /** A requested entry's name: none (null or blank) is the administration's. */
    private static String key(String name) {
        return name == null || name.isBlank() ? RequestContext.DEFAULT_ENTRY : name;
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
        problems.addAll(administrationProblems());
        problems.addAll(appPathProblems());
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

    /** The SPAs this application serves, whose prefixes the entries' app-paths should lie under (decision D43). */
    @Autowired(required = false)
    void spas(JabizWebProperties web) {
        this.web = web;
    }

    /**
     * An entry's {@code app-path} is the root of its frontend (decision D43 item 4): mailed links lead below it. It
     * should lie under an SPA served here, and under the SPA declaring the entry when one does
     * ({@code jabiz.web.spa[i].entry}); otherwise the links lead nowhere, or into another frontend (warnings).
     */
    private List<CheckProblem> appPathProblems() {
        JabizWebProperties served = web;
        List<CheckProblem> problems = new ArrayList<>();
        if (served == null) {
            return problems;
        }
        for (Entry entry : usable.values()) {
            String location = PROPERTY + "." + entry.name() + ".app-path";
            List<JabizWebProperties.Spa> own = served.spa().stream()
                .filter(spa -> entry.name().equals(spa.entry()) && spa.path() != null).toList();
            Optional<JabizWebProperties.Spa> owner = served.spaFor(entry.appPath());
            if (!own.isEmpty() && (owner.isEmpty() || !own.contains(owner.get()))) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, location, "'" + entry.appPath()
                    + "' is not under the SPA of entry " + entry.name() + " (" + String.join(", ", own.stream()
                    .map(JabizWebProperties.Spa::base).toList()) + "): its mailed links would open another frontend"));
            } else if (own.isEmpty() && owner.isEmpty()) {
                problems.add(CheckProblem.warning(SessionChecks.CATEGORY, location, "'" + entry.appPath()
                    + "' is under none of the SPAs served (jabiz.web.spa): its mailed links would lead nowhere"));
            }
        }
        return problems;
    }

    /**
     * Registered users must not reach the administration (docs/design/10-security.md section 15.7): while any entry
     * lets people register, the administration's entry may neither accept every role (as unconfigured, it does) nor
     * any role a registration grants; and it never lets people register itself.
     */
    private List<CheckProblem> administrationProblems() {
        List<CheckProblem> problems = new ArrayList<>();
        Config adminConfig = configured.get(RequestContext.DEFAULT_ENTRY);
        Entry admin = adminConfig == null ? usable.get(RequestContext.DEFAULT_ENTRY)
            : entry(RequestContext.DEFAULT_ENTRY, adminConfig);
        String location = PROPERTY + "." + RequestContext.DEFAULT_ENTRY + ".accepted-roles";
        if (admin.selfRegistration()) {
            // Whoever registers there (or names no entry) would get into the administration itself.
            problems.add(CheckProblem.error(SessionChecks.CATEGORY, PROPERTY + "." + RequestContext.DEFAULT_ENTRY
                + ".self-registration", "the administration entry cannot allow self-registration"));
        }
        configured.forEach((name, config) -> {
            Entry entry = entry(name, config);
            if (!entry.selfRegistration() || RequestContext.DEFAULT_ENTRY.equals(name)) {
                return;
            }
            if (admin.acceptedRoles().contains(ALL_ROLES)) {
                problems.add(CheckProblem.error(SessionChecks.CATEGORY, location, "the administration accepts every "
                    + "role while entry " + name + " lets people register: registered users could sign in to it; "
                    + "list the administration's roles"));
                return;
            }
            entry.registrationRoles().stream().filter(admin::accepts).sorted().forEach(role -> problems.add(
                CheckProblem.error(SessionChecks.CATEGORY, location, "the administration accepts " + role
                    + ", which entry " + name + " grants to whoever registers")));
        });
        return problems;
    }

    private List<String> errors(String name, Config config) {
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
        // Signing in by a mailed link proves only the mailbox (decision D54): never for the administration, nor for an
        // entry taking every role.
        if (entry.emailLink() && (RequestContext.DEFAULT_ENTRY.equals(name)
            || entry.acceptedRoles().contains(ALL_ROLES))) {
            errors.add("email-link cannot be on for the administration entry or an entry accepting every role");
        }
        // The administration's users leave through an administrator (SEC_USER_DELETION_SCHEDULE), never on their own
        // (decision D62 item 1): not at the administration, nor at an entry taking every role.
        if (entry.accountDeletion() && (RequestContext.DEFAULT_ENTRY.equals(name)
            || entry.acceptedRoles().contains(ALL_ROLES))) {
            errors.add("account-deletion cannot be on for the administration entry or an entry accepting every role");
        }
        // Another platform's word for a person (decision D59): never for the administration nor every role; and its
        // users have no address, so they could never pass a verified-address requirement.
        if (entry.externalToken() != null) {
            if (RequestContext.DEFAULT_ENTRY.equals(name) || entry.acceptedRoles().contains(ALL_ROLES)) {
                errors.add("external-token cannot be set for the administration entry or an entry accepting every "
                    + "role");
            }
            if (entry.requireVerifiedEmail()) {
                errors.add("external-token users have no e-mail address: require-verified-email cannot be on");
            }
        }
        if (entry.appPath() == null || !APP_PATH.matcher(entry.appPath()).matches()
            || List.of(entry.appPath().split("/")).stream().anyMatch(s -> s.equals(".") || s.equals(".."))) {
            errors.add("app-path must be an absolute path such as /portal/ (letters, digits, . _ ~ -), not '"
                + entry.appPath() + "'");
        }
        errors.addAll(sessionErrors(name, config == null ? null : config.session(), entry.session()));
        if (entry.session().longLived() && entry.acceptedRoles().contains(ALL_ROLES)) {
            // Long sessions are for the application's own front ends (decision D43 item 2), never every role.
            errors.add("accepted-roles cannot be " + ALL_ROLES + " for an entry with long sessions (persistence device "
                + "or idle-timeout 0): list its roles");
        }
        return errors;
    }

    /**
     * The session settings (decision D43 item 2): a lifetime that is positive and at most
     * {@link #MAX_REFRESH_TOKEN_TTL}, an idle timeout that is not negative, a known persistence, and never
     * {@code device} for the administration (a long session in a shared browser is a long session of an administrator).
     */
    private static List<String> sessionErrors(String name, SessionConfig configured, Session session) {
        List<String> errors = new ArrayList<>();
        // The bound is the entry's own: the global setting (which the administration has always used) stays as it is.
        boolean ownTtl = configured != null && configured.refreshTokenTtl() != null;
        if (!session.refreshTokenTtl().isPositive()
            || (ownTtl && session.refreshTokenTtl().compareTo(MAX_REFRESH_TOKEN_TTL) > 0)) {
            errors.add("session.refresh-token-ttl must be positive and at most " + MAX_REFRESH_TOKEN_TTL.toDays()
                + " days, not " + session.refreshTokenTtl());
        }
        if (session.idleTimeout().isNegative()) {
            errors.add("session.idle-timeout must not be negative (0: no idle end), not " + session.idleTimeout());
        }
        if (configured != null && configured.persistence() != null
            && Persistence.of(configured.persistence()).isEmpty()) {
            errors.add("session.persistence must be tab or device, not '" + configured.persistence() + "'");
        }
        if (RequestContext.DEFAULT_ENTRY.equals(name) && session.persistence() == Persistence.DEVICE) {
            errors.add("session.persistence cannot be device for the administration entry: its sessions stay in the "
                + "tab");
        }
        if (RequestContext.DEFAULT_ENTRY.equals(name) && !session.idleEnds() && !session.idleTimeout().isNegative()) {
            // The administration's idle lock (decision D28 item 5) stays.
            errors.add("session.idle-timeout cannot be 0 for the administration entry: its sessions end when idle");
        }
        return errors;
    }

    private Entry entry(String name, Config config) {
        Config c = config == null ? new Config(null, null, null, null, null, null, null, null, null) : config;
        SessionConfig s = c.session() == null ? new SessionConfig(null, null, null) : c.session();
        Session session = new Session(s.refreshTokenTtl() == null ? defaults.refreshTokenTtl() : s.refreshTokenTtl(),
            s.idleTimeout() == null ? defaults.idleTimeout() : s.idleTimeout(),
            Persistence.of(s.persistence()).orElse(Persistence.TAB));
        return new Entry(name, trimmed(c.acceptedRoles()), Boolean.TRUE.equals(c.selfRegistration()),
            trimmed(c.registrationRoles()), Boolean.TRUE.equals(c.requireVerifiedEmail()),
            c.appPath() == null ? "/" : c.appPath().trim(), session, Boolean.TRUE.equals(c.emailLink()),
            c.externalToken(), Boolean.TRUE.equals(c.accountDeletion()));
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
