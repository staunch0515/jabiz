package com.jabiz.app.it.fixture;

import com.jabiz.document.DocumentLayout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Document layouts that break the rules of docs/design/22-documents.md section 2.2, several ways at once; only the
 * {@code broken-documents} profile of {@code PlatformCheckIT} loads them.
 */
@Configuration
@Profile("broken-documents")
class ItBrokenDocumentFixtures {

    @Bean
    DocumentLayout itBrokenDocument() {
        return DocumentLayout.define("it.broken_document", d -> d
            .permissions("it.read")
            .subject("Nowhere", "orderId")
            .facts("commerce.order_document_header", "orderNo", "colour")
            .table("commerce.order_document_header", "orderNo")
            .table("it.no_such_template", "x")
            .note("missing"));
    }

    @Bean
    DocumentLayout itBrokenDocumentAgain() {
        return DocumentLayout.define("it.broken_document", d -> d.permissions("it.read").note("missing"));
    }
}
