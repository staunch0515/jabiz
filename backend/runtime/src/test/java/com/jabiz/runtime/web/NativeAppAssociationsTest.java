package com.jabiz.runtime.web;

import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.web.JabizWebProperties.AndroidApp;
import com.jabiz.runtime.web.JabizWebProperties.NativeApp;
import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The association files of the store apps (phase 17l, decision D68): Apple's for the SPA's own path only, Google's for
 * every Android app; 404 without apps; the settings checked at startup.
 */
class NativeAppAssociationsTest {

    private static final String FINGERPRINT = String.join(":", java.util.Collections.nCopies(32, "AB"));
    private static final List<Spa> SPAS = List.of(new Spa("/", null, null), new Spa("/taker", null, null),
        new Spa("/sponsor", null, null));

    private static JabizWebProperties properties(List<NativeApp> apps) {
        return new JabizWebProperties(SPAS, null, null, null, apps);
    }

    private static NativeAppAssociations files(List<NativeApp> apps) {
        return new NativeAppAssociations(properties(apps), tools.jackson.databind.json.JsonMapper.builder().build());
    }

    @Test
    void appleOpensOnlyTheSpasOwnPath() {
        NativeAppAssociations files = files(List.of(
            new NativeApp("/taker", List.of("ABCDE12345.com.example.app"), null),
            new NativeApp("/", List.of("ABCDE12345.com.example.site"), null)));
        assertThat(files.apple()).contains(Map.of("applinks", Map.of("apps", List.of(), "details", List.of(
            Map.of("appIDs", List.of("ABCDE12345.com.example.app"), "components", List.of(Map.of("/", "/taker/*"))),
            Map.of("appIDs", List.of("ABCDE12345.com.example.site"), "components", List.of(Map.of("/", "/*")))))));
        // No Android app: Google's file is left to the rest of the chain.
        assertThat(files.android()).isEmpty();
    }

    @Test
    void androidAppsHandleTheSitesLinksOnceEach() {
        AndroidApp app = new AndroidApp("com.example.app", List.of(FINGERPRINT.toLowerCase()));
        NativeAppAssociations files = files(List.of(
            new NativeApp("/taker", null, List.of(app)), new NativeApp("/sponsor", null, List.of(app))));
        assertThat(files.android()).contains(List.of(Map.of(
            "relation", List.of("delegate_permission/common.handle_all_urls"),
            "target", Map.of("namespace", "android_app", "package_name", "com.example.app",
                "sha256_cert_fingerprints", List.of(FINGERPRINT)))));
        assertThat(files.apple()).isEmpty();
        assertThat(files(null).android()).isEmpty();
    }

    @Test
    void theFilesAreNeverAnSpasRoute() {
        assertThat(SpaFallbackFilter.excluded(NativeAppAssociations.APPLE)).isTrue();
        assertThat(SpaFallbackFilter.excluded(NativeAppAssociations.ANDROID)).isTrue();
        assertThat(SpaFallbackFilter.excluded("/.well-known/security.txt")).isFalse();
    }

    @Test
    void everyWrongSettingIsReportedAtOnce() {
        assertThat(new SpaConfigCheck(properties(List.of(new NativeApp("/taker", List.of("ABCDE12345.com.example.app"),
            List.of(new AndroidApp("com.example.app", List.of(FINGERPRINT))))))).check()).isEmpty();
        List<CheckProblem> problems = new SpaConfigCheck(properties(List.of(
            new NativeApp("/nowhere", List.of("abcde12345.com.example"), null),
            new NativeApp("/taker", List.of("ABCDE12345"), List.of(new AndroidApp("app", List.of("AB:CD")),
                new AndroidApp(null, null))),
            new NativeApp("/taker", List.of("ABCDE12345.com.example.app"), null),
            new NativeApp("/sponsor", null, null)))).check();
        assertThat(problems).extracting(p -> p.location() + ": " + p.message()).containsExactly(
            "jabiz.web.native-apps[0].path: must be the path of a configured single-page application, was \"/nowhere\"",
            "jabiz.web.native-apps[0].ios[0]: must be <Team ID>.<bundle ID>, like \"ABCDE12345.com.example.app\", "
                + "was \"abcde12345.com.example\"",
            "jabiz.web.native-apps[1].ios[0]: must be <Team ID>.<bundle ID>, like \"ABCDE12345.com.example.app\", "
                + "was \"ABCDE12345\"",
            "jabiz.web.native-apps[1].android[0].package-name: must be an Android package like \"com.example.app\", "
                + "was \"app\"",
            "jabiz.web.native-apps[1].android[0].fingerprints[0]: must be 32 hexadecimal bytes separated by colons "
                + "(AA:BB:…), as keytool and the Play Console print it",
            "jabiz.web.native-apps[1].android[1].package-name: must be an Android package like \"com.example.app\", "
                + "was nothing",
            "jabiz.web.native-apps[1].android[1].fingerprints: at least one SHA-256 fingerprint of a signing "
                + "certificate",
            "jabiz.web.native-apps[2].path: \"/taker\" has its apps configured more than once",
            "jabiz.web.native-apps[3]: names no app (ios or android)");
    }
}
