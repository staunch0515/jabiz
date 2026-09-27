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

    private static List<CheckProblem> check(List<Spa> spas) {
        return new SpaConfigCheck(new JabizWebProperties(spas)).check();
    }
}
