package com.jabiz.runtime.security;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    private static OidcProvider withCallbacks(String id, String redirect, Map<String, String> callbacks) {
        return new OidcProvider(id, "https://idp.example.com", "client", "s", redirect, null, null, null, callbacks);
    }

    @Test
    void eachEntryHasItsOwnCallbackAndOnlyEntriesWithOneAreOffered() {
        OidcProvider both = withCallbacks("both", "https://erp.example.com/login/oidc",
            Map.of("portal", "https://erp.example.com/portal/login/oidc"));
        OidcProvider portalOnly = withCallbacks("portal-only", null,
            Map.of("portal", "http://localhost:5174/portal/login/oidc"));
        OidcProviders providers = new OidcProviders(List.of(both, portalOnly));

        assertThat(providers.check()).isEmpty();
        // The former redirect-uri is the administration's.
        assertThat(both.redirectUri(null)).isEqualTo("https://erp.example.com/login/oidc");
        assertThat(both.redirectUri("admin")).isEqualTo("https://erp.example.com/login/oidc");
        assertThat(both.redirectUri("portal")).isEqualTo("https://erp.example.com/portal/login/oidc");
        assertThat(both.redirectUri("shop")).isNull();
        assertThat(portalOnly.offers("admin")).isFalse();
        assertThat(providers.offeredTo("admin")).extracting(OidcProvider::id).containsExactly("both");
        assertThat(providers.offeredTo(null)).extracting(OidcProvider::id).containsExactly("both");
        assertThat(providers.offeredTo("portal")).extracting(OidcProvider::id).containsExactly("both", "portal-only");
        assertThat(providers.offeredTo("shop")).isEmpty();
        // redirect-uris.admin may stand for redirect-uri.
        assertThat(withCallbacks("a", null, Map.of("admin", "https://a.example/cb")).redirectUri(null))
            .isEqualTo("https://a.example/cb");
    }

    @Test
    void callbacksOfEntriesAreCheckedLikeTheAdministrations() {
        OidcProviders providers = new OidcProviders(List.of(
            withCallbacks("none", null, Map.of()),
            withCallbacks("bad", "https://a.example/cb#x", Map.of(
                "Portal", "https://a.example/portal/cb",
                "shop", "http://shop.example/cb",
                "site", "/site/cb",
                "admin", "https://a.example/other"))));

        assertThat(providers.check()).extracting(CheckProblem::message).containsExactly(
            "redirect-uri is required (or redirect-uris.<entry> for the entries offered the provider)",
            "redirect-uri must not have a fragment",
            "redirect-uris: 'Portal' is not the name of a sign-in entry",
            "redirect-uris.shop must be an absolute https URL (http only for localhost)",
            "redirect-uris.site must be an absolute https URL (http only for localhost)",
            "redirect-uri and redirect-uris.admin are both the administration's callback but differ: set one");
        assertThat(providers.all()).isEmpty();
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

    private static OidcProvider kind(String id, String kind, String issuer, boolean autoProvision, List<String> scopes,
        Map<String, String> callbacks) {
        return new OidcProvider(id, issuer, "client", "s", null, scopes, null, null, callbacks, kind, autoProvision);
    }

    @Test
    void kindsAndAutoProvisionAreChecked() {
        Map<String, String> portal = Map.of("portal", "https://shop.example.com/portal/login/oidc");
        OidcProvider google = kind("google", "Google", null, true, null, portal);
        assertThat(google.kind()).isEqualTo("google");
        assertThat(google.google()).isTrue();
        // Google's issuer is the default one.
        assertThat(google.issuer()).isEqualTo(OidcProvider.GOOGLE_ISSUER);
        assertThat(kind("corp", null, "https://idp.example.com", false, null, portal).kind()).isEqualTo("generic");
        assertThat(google.toString()).contains("kind=google", "autoProvision=true", "clientSecret=***");

        OidcProviders providers = new OidcProviders(List.of(google,
            kind("apple", "apple", "https://appleid.apple.com", false, null, portal),
            kind("noemail", null, "https://idp.example.com", true, List.of("openid", "profile"), portal)));
        assertThat(providers.check()).extracting(CheckProblem::message).containsExactly(
            "kind must be one of generic, google",
            "auto-provision needs the scope email");
        assertThat(providers.all()).extracting(OidcProvider::id).containsExactly("google");
    }

    @Test
    void aProviderThatCouldOpenAccountsNowhereIsWarnedAbout() {
        SignInEntries entries = new SignInEntries(Map.of(
            "portal", new SignInEntries.Config(List.of("CUSTOMER"), true, List.of("CUSTOMER"), null, null, null, null),
            "admin", new SignInEntries.Config(List.of("ADMIN"), null, null, null, null, null, null)), true,
            () -> List.of("CUSTOMER", "ADMIN"));
        OidcProvider adminOnly = new OidcProvider("staff", "https://idp.example.com", "client", "s",
            "https://erp.example.com/login/oidc", null, null, null, null, null, true);
        OidcProvider portal = kind("shop", null, "https://idp.example.com", true, null,
            Map.of("portal", "https://shop.example.com/portal/login/oidc"));
        OidcProviders providers = new OidcProviders(List.of(adminOnly, portal), () -> entries);

        assertThat(providers.check()).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.location()).contains("staff");
            assertThat(problem.message()).contains("opens no accounts");
        });
        assertThat(OidcProviders.provisioningEntries(portal, entries)).containsExactly("portal");
        assertThat(OidcProviders.provisioningEntries(adminOnly, entries)).isEmpty();
    }

    @Test
    void theAdministrationNeverOpensAccountsWhateverItsSettingsSay() {
        assertThat(OidcController.opensAccounts(new SignInEntries.Entry("admin", Set.of("ADMIN"), true,
            Set.of("ADMIN"), false, "/"))).isFalse();
        assertThat(OidcController.opensAccounts(new SignInEntries.Entry("portal", Set.of("CUSTOMER"), true,
            Set.of("CUSTOMER"), false, "/portal/"))).isTrue();
        assertThat(OidcController.opensAccounts(new SignInEntries.Entry("shop", Set.of("CUSTOMER"), false,
            Set.of(), false, "/shop/"))).isFalse();
    }

    @Test
    void googlesIssuerGetsGooglesRuleWhateverTheKindAndAWarning() {
        Map<String, String> portal = Map.of("portal", "https://shop.example.com/portal/login/oidc");
        OidcProvider old = kind("legacy", null, OidcProvider.GOOGLE_ISSUER, true, null, portal);
        assertThat(old.google()).isFalse();
        assertThat(old.googleRules()).isTrue();
        assertThat(kind("corp", null, "https://idp.example.com", true, null, portal).googleRules()).isFalse();
        assertThat(new OidcProviders(List.of(old)).check()).singleElement().satisfies(problem -> {
            assertThat(problem.severity()).isEqualTo(CheckProblem.Severity.WARNING);
            assertThat(problem.message()).contains("set kind: google");
        });
        assertThat(new OidcProviders(List.of(kind("google", "google", null, true, null, portal))).check()).isEmpty();
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
