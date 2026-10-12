package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Sign-in entries and their startup check (docs/design/10-security.md section 15; decision D36 item 1). */
class SignInEntriesTest {

    private static SignInEntries.Config config(List<String> accepted, Boolean registration, List<String> granted,
        Boolean verified, String appPath) {
        return new SignInEntries.Config(accepted, registration, granted, verified, appPath, null);
    }

    private static SignInEntries.Config sessions(List<String> accepted, Duration ttl, Duration idle,
        String persistence) {
        return new SignInEntries.Config(accepted, null, null, null, null,
            new SignInEntries.SessionConfig(ttl, idle, persistence));
    }

    @Test
    void withoutConfigurationTheAdministrationAcceptsEveryRole() {
        SignInEntries entries = new SignInEntries(Map.of(), false, null);

        SignInEntries.Entry admin = entries.find(null).orElseThrow();
        assertThat(admin.name()).isEqualTo("admin");
        assertThat(admin.accepts("ANYTHING")).isTrue();
        assertThat(admin.requireVerifiedEmail()).isFalse();
        assertThat(entries.find("admin")).contains(admin);
        assertThat(entries.find("portal")).isEmpty();
        assertThat(entries.all()).containsExactly(admin);
        assertThat(entries.check()).isEmpty();
        // An entry that is not configured accepts nobody (default deny).
        assertThat(entries.resolve("portal").accepts("CUSTOMER")).isFalse();
        assertThat(entries.resolve(null)).isEqualTo(admin);
    }

    @Test
    void configuredEntriesAndAnOverriddenAdministration() {
        SignInEntries entries = new SignInEntries(Map.of(
            "portal", config(List.of("CUSTOMER"), true, List.of("CUSTOMER"), true, "/portal/"),
            "admin", config(List.of("ADMIN", "CLERK"), null, null, null, null)), true, () -> List.of("CUSTOMER",
            "ADMIN", "CLERK"));

        SignInEntries.Entry portal = entries.find("portal").orElseThrow();
        assertThat(portal).isEqualTo(new SignInEntries.Entry("portal", Set.of("CUSTOMER"), true, Set.of("CUSTOMER"),
            true, "/portal/"));
        assertThat(portal.accepts("ADMIN")).isFalse();
        SignInEntries.Entry admin = entries.find(null).orElseThrow();
        assertThat(admin.accepts("ADMIN")).isTrue();
        assertThat(admin.accepts("CUSTOMER")).isFalse();
        assertThat(admin.appPath()).isEqualTo("/");
        assertThat(entries.check()).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        SignInEntries entries = new SignInEntries(Map.of(
            "Bad_Name", config(List.of("A"), null, null, null, null),
            "nobody", config(List.of(), null, null, null, null),
            "loose", config(List.of("A"), true, List.of("B"), null, "portal"),
            "empty", config(List.of("A"), true, List.of(), null, "/x/../y"),
            "wild", config(List.of("*"), true, List.of("*"), null, "/")), false, () -> List.of("A"));

        List<String> errors = entries.check().stream().filter(CheckProblem::isError)
            .map(p -> p.location() + ": " + p.message()).toList();
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("jabiz.security.entries.Bad_Name: the name"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("nobody: accepted-roles is empty"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("loose: registration role B is not among"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("loose: app-path must be an absolute path"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("empty: self-registration is on, but "
            + "registration-roles is empty"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("empty: app-path"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("wild: registration-roles cannot be *"));

        List<String> warnings = entries.check().stream().filter(p -> !p.isError()).map(CheckProblem::message)
            .toList();
        // Mail is off while entries let people register; role B does not exist.
        assertThat(warnings).anySatisfy(w -> assertThat(w).contains("jabiz.mail.enabled"));
        assertThat(warnings).anySatisfy(w -> assertThat(w).isEqualTo("role B does not exist (yet)"));
        // Only entries without errors are offered; the administration is still there.
        assertThat(entries.all()).extracting(SignInEntries.Entry::name).containsExactly("admin");
    }

    @Test
    void registeredUsersMustNotReachTheAdministration() {
        // Unconfigured, the administration accepts every role: an error while anyone may register.
        SignInEntries open = new SignInEntries(Map.of(
            "portal", config(List.of("CUSTOMER"), true, List.of("CUSTOMER"), true, "/")), true, null);
        assertThat(open.check()).filteredOn(CheckProblem::isError).singleElement().satisfies(p -> {
            assertThat(p.location()).isEqualTo("jabiz.security.entries.admin.accepted-roles");
            assertThat(p.message()).contains("accepts every role while entry portal lets people register");
        });

        // Configured, it must not accept a role a registration grants.
        SignInEntries overlapping = new SignInEntries(Map.of(
            "portal", config(List.of("CUSTOMER"), true, List.of("CUSTOMER"), true, "/"),
            "admin", config(List.of("ADMIN", "CUSTOMER"), null, null, null, null)), true, null);
        assertThat(overlapping.check()).filteredOn(CheckProblem::isError).singleElement().satisfies(p ->
            assertThat(p.message()).isEqualTo("the administration accepts CUSTOMER, which entry portal grants to "
                + "whoever registers"));

        // The administration never lets people register itself: they would register straight into it.
        SignInEntries selfRegistering = new SignInEntries(Map.of(
            "admin", config(List.of("ADMIN"), true, List.of("ADMIN"), null, null)), true, null);
        assertThat(selfRegistering.check()).filteredOn(CheckProblem::isError).singleElement().satisfies(p -> {
            assertThat(p.location()).isEqualTo("jabiz.security.entries.admin.self-registration");
            assertThat(p.message()).isEqualTo("the administration entry cannot allow self-registration");
        });

        // Without registration, or with the administration's own roles only, there is nothing to report.
        assertThat(new SignInEntries(Map.of("portal", config(List.of("CUSTOMER"), false, null, true, "/")), true,
            null).check()).isEmpty();
        assertThat(new SignInEntries(Map.of(
            "portal", config(List.of("CUSTOMER"), true, List.of("CUSTOMER"), true, "/"),
            "admin", config(List.of("ADMIN"), null, null, null, null)), true, null).check()).isEmpty();
    }

    @Test
    void entriesSetTheirOwnSessionsOrHaveTheGlobalOnes() {
        SignInEntries.Session global = new SignInEntries.Session(Duration.ofHours(4), Duration.ofMinutes(20),
            SignInEntries.Persistence.TAB);
        SignInEntries entries = new SignInEntries(Map.of(
            "app", sessions(List.of("PLAYER"), Duration.ofDays(30), Duration.ZERO, "device"),
            "shop", sessions(List.of("SELLER"), null, Duration.ofHours(1), null),
            "kiosk", config(List.of("KIOSK"), null, null, null, null)), false, null, global);

        assertThat(entries.session("app")).isEqualTo(new SignInEntries.Session(Duration.ofDays(30), Duration.ZERO,
            SignInEntries.Persistence.DEVICE));
        assertThat(entries.session("app").idleEnds()).isFalse();
        assertThat(entries.session("shop")).isEqualTo(new SignInEntries.Session(Duration.ofHours(4),
            Duration.ofHours(1), SignInEntries.Persistence.TAB));
        assertThat(entries.session("kiosk")).isEqualTo(global);
        // The administration, unconfigured, and an entry that went away: the global settings.
        assertThat(entries.session(null)).isEqualTo(global);
        assertThat(entries.session("gone")).isEqualTo(global);
        assertThat(entries.check()).isEmpty();
        assertThat(SignInEntries.Persistence.DEVICE.value()).isEqualTo("device");
    }

    @Test
    void theAdministrationNeverKeepsSessionsOnTheDeviceAndLifetimesAreBounded() {
        SignInEntries entries = new SignInEntries(Map.of(
            "admin", sessions(List.of("ADMIN"), null, null, "DEVICE"),
            "long", sessions(List.of("A"), Duration.ofDays(91), null, null),
            "none", sessions(List.of("A"), Duration.ZERO, null, null),
            "back", sessions(List.of("A"), null, Duration.ofMinutes(-1), null),
            "odd", sessions(List.of("A"), Duration.ofDays(90), null, "disk")), false, null);

        List<String> errors = entries.check().stream().filter(CheckProblem::isError)
            .map(p -> p.location() + ": " + p.message()).toList();
        assertThat(errors).anySatisfy(e -> assertThat(e).isEqualTo("jabiz.security.entries.admin: session.persistence "
            + "cannot be device for the administration entry: its sessions stay in the tab"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("jabiz.security.entries.long: "
            + "session.refresh-token-ttl must be positive and at most 90 days"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("jabiz.security.entries.none: "
            + "session.refresh-token-ttl must be positive"));
        assertThat(errors).anySatisfy(e -> assertThat(e).startsWith("jabiz.security.entries.back: "
            + "session.idle-timeout must not be negative"));
        assertThat(errors).anySatisfy(e -> assertThat(e).isEqualTo("jabiz.security.entries.odd: session.persistence "
            + "must be tab or device, not 'disk'"));
        assertThat(errors).hasSize(5);
        // Nor does the administration end its idle lock.
        assertThat(new SignInEntries(Map.of("admin", sessions(List.of("ADMIN"), null, Duration.ZERO, null)), false,
            null).check()).singleElement().satisfies(p -> {
                assertThat(p.isError()).isTrue();
                assertThat(p.message()).isEqualTo("session.idle-timeout cannot be 0 for the administration entry: "
                    + "its sessions end when idle");
            });
        // None of them is offered; the configured administration is not either (requests for it are refused).
        assertThat(entries.all()).isEmpty();
        // 90 days is the bound itself; the global lifetime is not bound (the administration has always used it).
        assertThat(new SignInEntries(Map.of("ok", sessions(List.of("A"), Duration.ofDays(90), null, "device")), false,
            null, new SignInEntries.Session(Duration.ofDays(120), Duration.ofMinutes(15),
                SignInEntries.Persistence.TAB)).check()).isEmpty();
    }

    @Test
    void longSessionsNeverAcceptEveryRole() {
        SignInEntries entries = new SignInEntries(Map.of(
            "kept", sessions(List.of("*"), null, null, "device"),
            "awake", sessions(List.of("A", "*"), null, Duration.ZERO, null),
            "plain", sessions(List.of("*"), null, null, null)), false, null);

        assertThat(entries.check()).filteredOn(CheckProblem::isError).extracting(p -> p.location() + ": "
            + p.message()).containsExactlyInAnyOrder(
                "jabiz.security.entries.kept: accepted-roles cannot be * for an entry with long sessions "
                    + "(persistence device or idle-timeout 0): list its roles",
                "jabiz.security.entries.awake: accepted-roles cannot be * for an entry with long sessions "
                    + "(persistence device or idle-timeout 0): list its roles");
        assertThat(entries.all()).extracting(SignInEntries.Entry::name).containsExactlyInAnyOrder("admin", "plain");
        assertThat(entries.session("plain").longLived()).isFalse();
        assertThat(new SignInEntries.Session(Duration.ofDays(1), Duration.ZERO, SignInEntries.Persistence.TAB)
            .longLived()).isTrue();
        assertThat(new SignInEntries.Session(Duration.ofDays(1), Duration.ofMinutes(15),
            SignInEntries.Persistence.DEVICE).longLived()).isTrue();
    }

    @Test
    void theIdleTimeoutOfEveryEntryIsCheckedAgainstTheAccessToken() {
        byte[] key = new byte[32];
        JwtService tokens = new JwtService(key, Duration.ofMinutes(15), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        MfaSettings settings = new MfaSettings("j", Duration.ofMinutes(5), Duration.ofMinutes(10), true,
            Duration.ofMinutes(15));
        SignInEntries entries = new SignInEntries(Map.of(
            "short", sessions(List.of("A"), null, Duration.ofMinutes(5), null),
            "brief", sessions(List.of("A"), null, Duration.ofMinutes(10), null),
            "never", sessions(List.of("A"), null, Duration.ZERO, null),
            "long", sessions(List.of("A"), null, Duration.ofHours(1), null)), false, null);

        assertThat(new SessionChecks(tokens, settings, entries).check()).extracting(CheckProblem::location)
            .containsExactlyInAnyOrder("jabiz.security.entries.short.session.idle-timeout",
                "jabiz.security.entries.brief.session.idle-timeout");
    }

    @Test
    void sessionsAreBoundFromTheEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.security.refresh-token-ttl", "PT2H")
            .withProperty("jabiz.security.entries.app.accepted-roles", "PLAYER")
            .withProperty("jabiz.security.entries.app.session.refresh-token-ttl", "30d")
            .withProperty("jabiz.security.entries.app.session.idle-timeout", "0")
            .withProperty("jabiz.security.entries.app.session.persistence", "device");
        SignInEntries entries = new SignInEntries(environment, null);

        assertThat(entries.session("app")).isEqualTo(new SignInEntries.Session(Duration.ofDays(30), Duration.ZERO,
            SignInEntries.Persistence.DEVICE));
        assertThat(entries.session("admin")).isEqualTo(new SignInEntries.Session(Duration.ofHours(2),
            Duration.ofMinutes(15), SignInEntries.Persistence.TAB));
    }

    @Test
    void roleCodesThatCannotBeReadAreAWarning() {
        SignInEntries entries = new SignInEntries(Map.of("portal", config(List.of("C"), null, null, null, null)),
            false, () -> {
                throw new IllegalStateException("no database");
            });
        assertThat(entries.check()).singleElement().satisfies(p -> {
            assertThat(p.isError()).isFalse();
            assertThat(p.message()).contains("could not be checked");
        });
    }

    @Test
    void entriesAreBoundFromTheEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("jabiz.security.entries.portal.accepted-roles", "CUSTOMER")
            .withProperty("jabiz.security.entries.portal.require-verified-email", "true")
            .withProperty("jabiz.security.entries.portal.app-path", "/portal/");
        SignInEntries entries = new SignInEntries(environment, null);

        SignInEntries.Entry portal = entries.find("portal").orElseThrow();
        assertThat(portal.acceptedRoles()).containsExactly("CUSTOMER");
        assertThat(portal.requireVerifiedEmail()).isTrue();
        assertThat(entries.find("admin")).isPresent();
    }
}
