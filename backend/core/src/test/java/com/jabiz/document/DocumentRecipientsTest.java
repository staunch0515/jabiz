package com.jabiz.document;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRecipientsTest {

    @Test
    void aColumnMayHoldSeveralAddressesOnceEach() {
        assertThat(DocumentRecipients.split(" ap@example.com; AP@example.com,\r\nboss@example.com ,"))
            .containsExactly("ap@example.com", "boss@example.com");
        assertThat(DocumentRecipients.split(null)).isEmpty();
        assertThat(DocumentRecipients.split("  ")).isEmpty();
    }

    @Test
    void onlyThePlainAddressesOfAColumnAreKept() {
        assertThat(DocumentRecipients.plain("John <j@example.com>; ap@example.com, foo@bar, " + "x".repeat(320)
            + "@example.com")).containsExactly("ap@example.com");
        assertThat(DocumentRecipients.plain(null)).isEmpty();
    }

    @Test
    void onlyPlainAddressesAreValid() {
        assertThat(DocumentRecipients.valid("ap.team+inv@mail.example.co.jp")).isTrue();
        assertThat(DocumentRecipients.valid("a@example.com\r\nBcc: spy@example.com")).isFalse();
        assertThat(DocumentRecipients.valid("Buyer <buyer@example.com>")).isFalse();
        assertThat(DocumentRecipients.valid("a@b")).isFalse();
        assertThat(DocumentRecipients.valid("a b@example.com")).isFalse();
        assertThat(DocumentRecipients.valid(null)).isFalse();
        assertThat(DocumentRecipients.valid("a".repeat(310) + "@example.com")).isFalse();
    }

    @Test
    void addressesOutsideTheAllowedOnesIgnoringCase() {
        assertThat(DocumentRecipients.outside(List.of("AP@Example.com", "x@example.com"), List.of("ap@example.com")))
            .containsExactly("x@example.com");
        assertThat(DocumentRecipients.distinct(List.of("A@x.com", "a@X.com", "b@x.com")))
            .containsExactly("A@x.com", "b@x.com");
    }
}
