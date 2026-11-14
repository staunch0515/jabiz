package com.jabiz.quizbuks.fee;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M13: what an invoice was paid with is one definition in two templates (the sponsor's, which the document and the
 * sponsor's page read, and finance's): below their headers, the statements are the same text, so that a change to one
 * that forgets the other fails here.
 */
class InvoiceSourcesTextTest {

    private static String body(String path) throws IOException {
        try (var in = InvoiceSourcesTextTest.class.getResourceAsStream(path)) {
            assertThat(in).as(path).isNotNull();
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int end = text.indexOf("---*/");
            assertThat(end).as("the header of " + path).isPositive();
            return text.substring(end + "---*/".length()).replaceAll("\\s+", " ").trim();
        }
    }

    @Test
    void theSponsorsAndFinancesStatementsAreTheSameTextBelowTheirHeaders() throws IOException {
        assertThat(body("/queries/qb/admin/fee-invoice-sources.sql"))
            .isEqualTo(body("/queries/qb/sponsor/fee-invoice-sources.sql"));
    }
}
