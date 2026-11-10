package com.jabiz.quizbuks.verification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** F-M3: what a domain is, which are public mail services, when an address is on a domain, when one is verified. */
class VerificationRulesTest {

    @Test
    void aDomainIsAHostNameOfTwoLabelsOrMore() {
        assertThat(VerificationRules.domain(" WWW.Acme.co.jp. ")).contains("acme.co.jp");
        assertThat(VerificationRules.domain("tokyo.acme.example")).contains("tokyo.acme.example");
        assertThat(VerificationRules.domain("xn--eckwd4c7c.xn--zckzah")).contains("xn--eckwd4c7c.xn--zckzah");
        for (String bad : new String[] {null, "", "acme", "https://acme.example", "acme.example/about",
            "boss@acme.example", "acme.example:8080", "10.0.0.1", "-acme.example", "acme-.example", "acme..example",
            "acme.e", "acme.123", "アクメ.jp", "acme example.jp", "a".repeat(250) + ".jp"}) {
            assertThat(VerificationRules.domain(bad)).as(String.valueOf(bad)).isEqualTo(Optional.empty());
        }
    }

    @Test
    void publicMailServicesAndTheirSubdomainsAreRefused() {
        assertThat(VerificationRules.publicMail("gmail.com")).isTrue();
        assertThat(VerificationRules.publicMail("mail.qq.com")).isTrue();
        assertThat(VerificationRules.publicMail("yahoo.co.jp")).isTrue();
        assertThat(VerificationRules.publicMail("acme.co.jp")).isFalse();
        // Not a suffix of a label: notgmail.com is somebody's.
        assertThat(VerificationRules.publicMail("notgmail.com")).isFalse();
        assertThat(VerificationRules.publicMail(null)).isFalse();
    }

    @Test
    void anAddressIsOnTheDomainOrASubdomain() {
        assertThat(VerificationRules.addressOn("info@acme.example", "acme.example")).isTrue();
        assertThat(VerificationRules.addressOn(" Info@Tokyo.ACME.example ", "acme.example")).isTrue();
        assertThat(VerificationRules.addressOn("info@notacme.example", "acme.example")).isFalse();
        assertThat(VerificationRules.addressOn("info@acme.example.evil", "acme.example")).isFalse();
        assertThat(VerificationRules.addressOn("a@b@acme.example", "acme.example")).isFalse();
        assertThat(VerificationRules.addressOn("info @acme.example", "acme.example")).isFalse();
        assertThat(VerificationRules.addressOn("@acme.example", "acme.example")).isFalse();
        assertThat(VerificationRules.addressOn(null, "acme.example")).isFalse();
        assertThat(VerificationRules.address(" Info@Acme.Example ")).isEqualTo("info@acme.example");
    }

    @Test
    void verifiedIsAnApprovedSubmissionAndANewOneEndsWhatStood() {
        assertThat(VerificationRules.verified(List.of("REJECTED", "APPROVED"))).isTrue();
        assertThat(VerificationRules.verified(List.of("REVIEW", "SUPERSEDED", "REVOKED"))).isFalse();
        assertThat(VerificationRules.verified(null)).isFalse();
        assertThat(VerificationRules.endedBy("LINK_SENT")).isEqualTo("WITHDRAWN");
        assertThat(VerificationRules.endedBy("REVIEW")).isEqualTo("WITHDRAWN");
        assertThat(VerificationRules.endedBy("APPROVED")).isEqualTo("SUPERSEDED");
        assertThat(VerificationRules.endedBy("REJECTED")).isNull();
        assertThat(VerificationRules.revocable("APPROVED")).isTrue();
        assertThat(VerificationRules.revocable("REVIEW")).isFalse();
    }
}
