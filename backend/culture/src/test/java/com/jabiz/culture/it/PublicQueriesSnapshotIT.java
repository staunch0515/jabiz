package com.jabiz.culture.it;

import com.jabiz.runtime.test.PublicQueriesSnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The public templates' catalog, kept as {@code site/src/api/public-queries.json} (docs/culture/00-design.md section
 * 7.2; docs/design/15-public-access.md section 7): the contract the public site generates its types from with
 * {@code pnpm gen:api}. {@code -Dpublic-queries.update-snapshot=true} rewrites it.
 */
class PublicQueriesSnapshotIT extends CultureItSupport {

    @Test
    void theCommittedCatalogDescribesThePublicTemplates() {
        String catalog = PublicQueriesSnapshot.render(context);
        assertThat(catalog).contains("\"culture.public.stories\"", "\"culture.public.search\"");

        PublicQueriesSnapshot.verify(context);
    }
}
