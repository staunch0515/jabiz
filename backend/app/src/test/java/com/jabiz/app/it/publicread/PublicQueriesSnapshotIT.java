package com.jabiz.app.it.publicread;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.PublicQueriesSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public templates' catalog, kept in the repository as {@code frontend/openapi/public-queries.json}
 * (docs/design/15-public-access.md section 7): the contract public frontends generate their types from.
 * {@code -Dpublic-queries.update-snapshot=true} rewrites it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PublicQueriesSnapshotIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theCommittedCatalogDescribesThePublicTemplates() {
        String catalog = PublicQueriesSnapshot.render(context);
        // Only public templates, never the back office's.
        assertThat(catalog).contains("\"commerce.public.catalog\"").doesNotContain("commerce.stock_availability");

        PublicQueriesSnapshot.verify(context);
    }
}
