package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OidcProvidersTest {

    private static OidcProvider provider(String id, String issuer, String redirect, String secret, List<String> scopes) {
        return new OidcProvider(id, issuer, "client", secret, redirect, scopes, Map.of("en", "Corp", "ja", "社内"),
            List.of("hwk"));
    }

    private static OidcProvider good(String id) {
        return provider(id, "https://idp.example.com/realm", "https://erp.example.com/login/oidc", "s", null);
    }

    @Test
    void everyProblemOfEveryProviderIsReportedAndOnlyGoodProvidersAreOffered() {
        OidcProviders providers = new OidcProviders(List.of(
            good("corp"),
            good("corp"),
            provider("Bad Id", "http://idp.example.com", "/login/oidc", " ", List.of("profile")),
            provider("dev", "http://localhost:8081/realms/dev", "http://127.0.0.1:5173/login/oidc", "s", null)));

        List<CheckProblem> problems = providers.check();
        assertThat(problems).extracting(CheckProblem::message).containsExactlyInAnyOrder(
            "id is used more than once",
            "id must be lower-case letters, digits and hyphens",
            "issuer must be an absolute https URL (http only for localhost)",
            "redirect-uri must be an absolute https URL (http only for localhost)",
            "client-secret is required (from the environment)",
            "scopes must include openid");
        assertThat(problems).allSatisfy(problem -> assertThat(problem.category()).isEqualTo("SECURITY"));
        assertThat(providers.all()).extracting(OidcProvider::id).containsExactly("corp", "dev");
        assertThat(providers.find("corp")).isPresent();
        assertThat(providers.find("Bad Id")).isEmpty();
        assertThat(providers.find(null)).isEmpty();
    }

    @Test
    void labelsFallBackToEnglishThenTheId() {
        OidcProvider corp = good("corp");
        assertThat(corp.label("ja")).isEqualTo("社内");
        assertThat(corp.label("zh")).isEqualTo("Corp");
        assertThat(new OidcProvider("x", "https://a", "c", "s", "https://b", null, null, null).label("en"))
            .isEqualTo("x");
        assertThat(corp.scopes()).containsExactly("openid", "profile", "email");
        assertThat(corp.toString()).doesNotContain("s,").contains("clientSecret=***");
    }

    @Test
    void onlyHttpsOrTheLocalMachineCarriesSignIns() {
        assertThat(OidcProvider.secureUrl("https://idp.example.com/x")).isTrue();
        assertThat(OidcProvider.secureUrl("http://localhost:8080/x")).isTrue();
        assertThat(OidcProvider.secureUrl("http://127.0.0.1/x")).isTrue();
        assertThat(OidcProvider.secureUrl("http://idp.example.com")).isFalse();
        assertThat(OidcProvider.secureUrl("ftp://localhost")).isFalse();
        assertThat(OidcProvider.secureUrl("/relative")).isFalse();
        assertThat(OidcProvider.secureUrl("not a url")).isFalse();
    }
}
