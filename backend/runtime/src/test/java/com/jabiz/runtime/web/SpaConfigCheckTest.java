package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpaConfigCheckTest {

    @Test
    void theDefaultAndAValidPairPass() {
        assertThat(check(null)).isEmpty();
        assertThat(check(List.of(new Spa("/", null, "default-src 'self'"), new Spa("/admin", "/admin/index.html", null),
            new Spa("/a/b-2", null, null)))).isEmpty();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        List<CheckProblem> problems = check(List.of(
            new Spa("admin", null, null),
            new Spa("/admin/", null, null),
            new Spa("/Admin", null, null),
            new Spa(null, null, null),
            new Spa("/x", null, null),
            new Spa("/x", null, null),
            new Spa("/api", null, null),
            new Spa("/actuator/x", null, null),
            new Spa("/y", "index.html", null),
            new Spa("/z", "/z/../secret.txt", null),
            new Spa("/w", null, " "),
            new Spa("/v", null, "default-src 'self'\r\nSet-Cookie: a=b")));

        assertThat(problems).allSatisfy(p -> {
            assertThat(p.category()).isEqualTo("WEB");
            assertThat(p.isError()).isTrue();
        });
        assertThat(problems).extracting(CheckProblem::location).containsExactly(
            "jabiz.web.spa[0].path",
            "jabiz.web.spa[1].path",
            "jabiz.web.spa[2].path",
            "jabiz.web.spa[3].path",
            "jabiz.web.spa[5].path",
            "jabiz.web.spa[6].path",
            "jabiz.web.spa[7].path",
            "jabiz.web.spa[8].index",
            "jabiz.web.spa[9].index",
            "jabiz.web.spa[10].content-security-policy",
            "jabiz.web.spa[11].content-security-policy");
        assertThat(problems.get(4).message()).contains("more than once");
        assertThat(problems.get(5).message()).contains("/api or /actuator");
    }

    @Test
    void unknownEntriesAreErrorsAndDifferencesFromTheJarWarnings() {
        List<Spa> built = List.of(
            new Spa("/", null, null, "admin", null),
            new Spa("/portal", null, null, "portal", JabizWebProperties.Pwa.ON),
            new Spa("/shop", null, null, "shop", JabizWebProperties.Pwa.ON));
        List<Spa> configured = List.of(
            new Spa("/", null, null, "admin", null),
            new Spa("/portal", null, null, "customers", JabizWebProperties.Pwa.OFF),
            new Spa("/extra", null, null, "Bad Entry", null));

        List<CheckProblem> problems = new SpaConfigCheck(new JabizWebProperties(configured), built,
            name -> List.of("admin", "portal", "shop").contains(name)).check();

        assertThat(problems).extracting(CheckProblem::isError, CheckProblem::location).containsExactly(
            org.assertj.core.groups.Tuple.tuple(true, "jabiz.web.spa[2].entry"),
            org.assertj.core.groups.Tuple.tuple(true, "jabiz.web.spa[1].entry"),
            org.assertj.core.groups.Tuple.tuple(false, "jabiz.web.spa (/portal)"),
            org.assertj.core.groups.Tuple.tuple(false, "jabiz.web.spa (/portal)"),
            org.assertj.core.groups.Tuple.tuple(false, "jabiz.web.spa"),
            org.assertj.core.groups.Tuple.tuple(false, "jabiz.web.spa (/extra)"));
        assertThat(problems.get(1).message()).contains("no usable sign-in entry customers");
        assertThat(problems.get(2).message()).contains("entry is customers, but the SPA was built for portal");
        assertThat(problems.get(3).message()).contains("pwa is off, but the SPA was built with on");
        assertThat(problems.get(4).message()).contains("the jar contains the SPA /shop");
        assertThat(problems.get(5).message()).contains("the jar contains no SPA /extra");

        // As built: nothing to report.
        assertThat(new SpaConfigCheck(new JabizWebProperties(built), built, name -> true).check()).isEmpty();
    }

    @Test
    void theEnvironmentIsATestSystemUnlessItSaysProduction() {
        assertThat(new JabizWebProperties(null).environment()).isEqualTo(JabizWebProperties.TEST);
        assertThat(new JabizWebProperties(null).production()).isFalse();
        assertThat(new JabizWebProperties(null, " ").environment()).isEqualTo(JabizWebProperties.TEST);
        assertThat(new JabizWebProperties(null, "Production").production()).isTrue();
        assertThat(new JabizWebProperties(null, "staging").environment()).isEqualTo("staging");
        assertThat(new JabizWebProperties(null, "staging").production()).isFalse();
        assertThat(new SpaConfigCheck(new JabizWebProperties(null, "UAT 2")).check()).isEmpty();
    }

    @Test
    void anEnvironmentNameThatCouldNotBeShownIsReported() {
        List<CheckProblem> problems = new SpaConfigCheck(new JabizWebProperties(null, "<b>prod</b>")).check();
        assertThat(problems).hasSize(1);
        assertThat(problems.getFirst().isError()).isTrue();
        assertThat(problems.getFirst().location()).isEqualTo("jabiz.web.environment");
        assertThat(new SpaConfigCheck(new JabizWebProperties(null, "a".repeat(33))).check()).hasSize(1);
    }

    private static List<CheckProblem> check(List<Spa> spas) {
        return new SpaConfigCheck(new JabizWebProperties(spas)).check();
    }
}
