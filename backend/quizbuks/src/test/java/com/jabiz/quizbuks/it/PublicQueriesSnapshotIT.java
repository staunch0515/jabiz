package com.jabiz.quizbuks.it;

import com.jabiz.runtime.test.PostgresIntegrationTest;
import com.jabiz.runtime.test.PublicQueriesSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public templates' catalog, kept as {@code backend/quizbuks/src/test/resources/public-queries.json} (platform
 * docs/design/15-public-access.md section 7; Q7b): the four templates of the help centre and the legal pages, nothing
 * else. {@code -Dpublic-queries.update-snapshot=true} rewrites it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class PublicQueriesSnapshotIT extends PostgresIntegrationTest {

    @Autowired
    ApplicationContext context;

    @Test
    void theCommittedCatalogDescribesThePublicTemplates() {
        String catalog = PublicQueriesSnapshot.render(context);
        assertThat(catalog).contains("\"qb.public.legal\"", "\"qb.public.legal-version\"",
            "\"qb.public.legal-history\"", "\"qb.public.faq\"").doesNotContain("qb.me.", "qb.admin.", "qb.system.",
            "qb.app.", "qb.sponsor.");

        PublicQueriesSnapshot.verify(context);
    }
}
