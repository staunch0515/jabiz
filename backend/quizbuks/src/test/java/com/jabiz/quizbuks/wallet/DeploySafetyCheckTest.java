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
        assertThat(DeploySafetyCheck.everyAddress("0.0.0.0/8")).isFalse();
        assertThat(DeploySafetyCheck.everyAddress("0.0.0.0")).isFalse();
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
