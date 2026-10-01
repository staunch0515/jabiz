package com.jabiz.document;

import com.jabiz.entity.SemanticKind;
import com.jabiz.report.ReportColumn;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentLayoutTest {

    static final DocumentLayout INVOICE = DocumentLayout.define("fin.invoice", d -> d
        .permissions("fin.ar.read")
        .subject("FinInvoice", "invoiceId")
        .number("fin.invoice_header", "invoiceNo")
        .party("seller", "fin.invoice_company", "name", "address")
        .party("billTo", "fin.invoice_header", "customerName", "billingAddress")
        .facts("fin.invoice_header", "invoiceNo", "invoiceDate", "dueDate")
        .table("fin.invoice_lines", "description", "quantity", "amount")
        .totals("fin.invoice_header", "subtotal", "tax", "total")
        .text("remittance", "fin.invoice_company", "remittance")
        .note("thanks"));

    @Test
    void aLayoutKnowsItsTemplatesColumnsAndTexts() {
        assertThat(INVOICE.templates()).containsExactly("fin.invoice_company", "fin.invoice_header",
            "fin.invoice_lines");
        assertThat(INVOICE.singleRow("fin.invoice_header")).isTrue();
        assertThat(INVOICE.singleRow("fin.invoice_company")).isTrue();
        assertThat(INVOICE.singleRow("fin.invoice_lines")).isFalse();
        assertThat(INVOICE.columnsOf("fin.invoice_header")).containsExactly("customerName", "billingAddress",
            "invoiceNo", "invoiceDate", "dueDate", "subtotal", "tax", "total");
        assertThat(INVOICE.columnsOf("fin.invoice_company")).containsExactly("name", "address", "remittance");
        assertThat(INVOICE.requiredMessages()).containsExactly("document.fin.invoice", "document.fin.invoice.seller",
            "document.fin.invoice.billTo", "document.fin.invoice.remittance", "document.fin.invoice.thanks");
        assertThat(INVOICE.labelKey("total")).isEqualTo("document.fin.invoice.total");
    }

    @Test
    void theNumbersTemplateIsReadForOneRowEvenWhenNoBlockShowsIt() {
        DocumentLayout layout = DocumentLayout.define("a.b", d -> d.permissions("p").number("a.no", "no")
            .table("a.lines", "x"));
        assertThat(layout.templates()).containsExactly("a.lines", "a.no");
        assertThat(layout.singleRow("a.no")).isTrue();
        assertThat(layout.columnsOf("a.no")).containsExactly("no");
        assertThat(layout.subjectEntity()).isNull();
    }

    @Test
    void theVersionChangesWithAnythingTheLayoutShowsOrNeeds() {
        String version = INVOICE.version();
        assertThat(version).hasSize(64).isEqualTo(DocumentLayoutTest.INVOICE.version());
        assertThat(DocumentLayout.define("fin.invoice", d -> d.permissions("fin.ar.read")
            .subject("FinInvoice", "invoiceId").number("fin.invoice_header", "invoiceNo")
            .party("seller", "fin.invoice_company", "name", "address")
            .party("billTo", "fin.invoice_header", "customerName", "billingAddress")
            .facts("fin.invoice_header", "invoiceNo", "invoiceDate", "dueDate")
            .table("fin.invoice_lines", "description", "amount", "quantity")
            .totals("fin.invoice_header", "subtotal", "tax", "total")
            .text("remittance", "fin.invoice_company", "remittance").note("thanks")).version())
            .isNotEqualTo(version);
        assertThat(INVOICE.describe()).containsEntry("id", "fin.invoice").containsEntry("subjectParam", "invoiceId");
        assertThat(INVOICE.describe().get("blocks").toString()).contains("block=party", "key=seller",
            "block=note", "block=text");
    }

    @Test
    void malformedLayoutsAreRefused() {
        assertThatThrownBy(() -> DocumentLayout.define("Invoice", d -> d.permissions("p")))
            .hasMessageContaining("dotted lowercase");
        assertThatThrownBy(() -> new DocumentLayout("a.b", List.of("p"), "E", null, null, List.of()))
            .hasMessageContaining("subject");
        assertThatThrownBy(() -> DocumentLayout.define("a.b", d -> d.note("x").note("x")))
            .hasMessageContaining("used twice");
        assertThatThrownBy(() -> DocumentLayout.define("a.b", d -> d.note("bad key")))
            .hasMessageContaining("key 'bad key'");
        assertThatThrownBy(() -> DocumentLayout.define("a.b", d -> d.facts("a.t")))
            .hasMessageContaining("shows no columns");
        assertThatThrownBy(() -> new DocumentLayout.Column(null, "c")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void contentFindsColumnsAndValuesByNameAndChecksItsRows() {
        DocumentContent.Source header = new DocumentContent.Source("t", "v",
            List.of(new ReportColumn("invoiceNo", "Invoice", new SemanticKind.Text(20, false)),
                new ReportColumn("total", "Total", new SemanticKind.Monetary("USD", 2))),
            List.of(List.of("INV-1", new java.math.BigDecimal("10.00"))));
        DocumentContent content = new DocumentContent("fin.invoice", "v", "Invoice", "INV-1", null, "en",
            Instant.EPOCH, false, Map.of("document.fin.invoice.thanks", "Thanks"), Map.of("t", header));
        assertThat(content.company()).isEmpty();
        assertThat(content.source("t").first("INVOICENO")).isEqualTo("INV-1");
        assertThat(content.source("t").column("total").label()).isEqualTo("Total");
        assertThat(content.label("document.fin.invoice.thanks", "x")).isEqualTo("Thanks");
        assertThat(content.label("document.fin.invoice.none", "x")).isEqualTo("x");
        assertThat(new DocumentContent.Source("e", "v", header.columns(), List.of()).first("total")).isNull();
        assertThatThrownBy(() -> content.source("nothing")).hasMessageContaining("nothing");
        assertThatThrownBy(() -> header.column("missing")).hasMessageContaining("missing");
        assertThatThrownBy(() -> header.first("missing")).hasMessageContaining("missing");
        assertThatThrownBy(() -> new DocumentContent.Source("t", "v", header.columns(), List.of(List.of("one"))))
            .hasMessageContaining("1 values for 2 columns");
    }
}
