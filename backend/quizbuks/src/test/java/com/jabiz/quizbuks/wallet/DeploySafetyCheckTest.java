package com.jabiz.quizbuks.wallet;

import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The deployment's safety settings (Q10b, reviews M6, L1, L5). */
class DeploySafetyCheckTest {

    @Test
    void trustedProxiesAreListedOrNoProxyIsDeclared() {
        // Listed: fine in both modes.
        assertThat(DeploySafetyCheck.proxyProblems(List.of("10.0.0.5", "10.1.0.0/16"), false, true)).isEmpty();
        // None: a warning in test mode, an error in live mode; declared direct exposure: nothing.
        assertThat(DeploySafetyCheck.proxyProblems(List.of(), false, false)).singleElement()
            .satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.WARNING));
        assertThat(DeploySafetyCheck.proxyProblems(List.of(" "), false, true)).singleElement()
            .satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR));
        assertThat(DeploySafetyCheck.proxyProblems(List.of(), true, true)).isEmpty();
        assertThat(DeploySafetyCheck.proxyProblems(List.of(), true, false)).isEmpty();
    }

    @Test
    void everyAddressIsNeverTrusted() {
        for (String all : List.of("0.0.0.0/0", "::/0", " 10.0.0.0/0 ", "::0/00")) {
            for (boolean live : List.of(true, false)) {
                for (boolean direct : List.of(true, false)) {
                    assertThat(DeploySafetyCheck.proxyProblems(List.of("10.0.0.5", all), direct, live)).as(all)
                        .singleElement().satisfies(p -> {
                            assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR);
                            assertThat(p.toString()).contains(DeploySafetyCheck.TRUSTED_PROXIES);
                        });
                }
            }
        }
        assertThat(DeploySafetyCheck.tooWide("0.0.0.0/8")).isFalse();
        assertThat(DeploySafetyCheck.tooWide("0.0.0.0")).isFalse();
    }

    /** Review L6: the prefix is read as the platform reads it, and ranges wider than any proxy's are refused. */
    @Test
    void rangesWiderThanAProxysAreNeverTrusted() {
        for (String wide : List.of("0.0.0.0/+0", "0.0.0.0/-0", "10.0.0.0/+00", "::/+0", "10.0.0.0/7", "1.0.0.0/1",
            "2001:db8::/31", "2000::/3", "::ffff:0:0/96", "::ffff:0.0.0.0/96", "::ffff:10.0.0.0/100", "::/96",
            "::/32", "64:ff9b::/96", "64:ff9b::/64", "64:ff9b:1::/48", "[::ffff:0:0]/96", "10.0.0.0/33", "::/129",
            "10.0.0.0/x", "10.0.0.0/", "proxy.example", "10.0.0.256", "10.0.0.0/-8")) {
            assertThat(DeploySafetyCheck.tooWide(wide)).as(wide).isTrue();
            assertThat(DeploySafetyCheck.proxyProblems(List.of(wide), false, false)).as(wide).singleElement()
                .satisfies(p -> assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR));
        }
        for (String narrow : List.of("10.0.0.5", "10.0.0.0/8", "10.0.0.0/+16", " 192.168.1.0/24 ", "2001:db8::/32",
            "2001:db8::1", "fd00::/48", "::1", "[::1]", "::ffff:10.0.0.0/104", "::ffff:10.0.0.1", "10.0.0.0/32",
            "64:ff9b::a00:1/128")) {
            assertThat(DeploySafetyCheck.tooWide(narrow)).as(narrow).isFalse();
        }
    }

    /** Review L5: secrets the entrypoint generated are for a local demonstration, never next to live mode. */
    @Test
    void generatedSecretsAreForTestModeOnly() {
        assertThat(DeploySafetyCheck.secretProblems(true, false)).isEmpty();
        assertThat(DeploySafetyCheck.secretProblems(false, true)).isEmpty();
        assertThat(DeploySafetyCheck.secretProblems(true, true)).singleElement().satisfies(p -> {
            assertThat(p.severity()).isEqualTo(CheckProblem.Severity.ERROR);
            assertThat(p.toString()).contains(DeploySafetyCheck.SECRETS_GENERATED);
        });
    }

    @Test
    void administrationWithoutASecondFactorIsForTestModeOnly() {
        assertThat(DeploySafetyCheck.mfaProblems(true, true)).isEmpty();
        assertThat(DeploySafetyCheck.mfaProblems(false, false)).isEmpty();
        assertThat(DeploySafetyCheck.mfaProblems(false, true)).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(DeploySafetyCheck.MFA_ADMINISTRATION));
    }

    @Test
    void theDevProfileStaysOnThisMachine() {
        assertThat(DeploySafetyCheck.devProblems(false, null, "https://quizbuks.example")).isEmpty();
        for (String address : List.of("127.0.0.1", "localhost", "::1", "[::1]", "127.0.1.1")) {
            assertThat(DeploySafetyCheck.devProblems(true, address, "http://localhost:8080")).as(address).isEmpty();
        }
        assertThat(DeploySafetyCheck.devProblems(true, "127.0.0.1", null)).isEmpty();
        assertThat(DeploySafetyCheck.devProblems(true, "127.0.0.1", "http://127.0.0.1:5175/")).isEmpty();
        for (String address : List.of("0.0.0.0", "::", "192.168.1.10", "example.com", "")) {
            assertThat(DeploySafetyCheck.devProblems(true, address, null)).as(address).singleElement()
                .satisfies(p -> assertThat(p.toString()).contains(DeploySafetyCheck.SERVER_ADDRESS));
        }
        assertThat(DeploySafetyCheck.devProblems(true, null, null)).hasSize(1);
        assertThat(DeploySafetyCheck.devProblems(true, "127.0.0.1", "https://quizbuks.example")).singleElement()
            .satisfies(p -> assertThat(p.toString()).contains(DeploySafetyCheck.MAIL_BASE_URL));
        assertThat(DeploySafetyCheck.devProblems(true, "0.0.0.0", "https://localhost.example")).hasSize(2);
    }
}
