package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Sign-in entries and their startup check (docs/design/10-security.md section 15; decision D36 item 1). */
class SignInEntriesTest {

    private static SignInEntries.Config config(List<String> accepted, Boolean registration, List<String> granted,
        Boolean verified, String appPath) {
        return new SignInEntries.Config(accepted, registration, granted, verified, appPath);
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
