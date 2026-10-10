package com.jabiz.runtime.entity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Indexes on lower(column) as PostgreSQL prints them (decision D36: uniqueness regardless of case). */
class IndexExpressionsTest {

    @Test
    void theExactColumnOfALowerExpressionCounts() {
        String varchar = "CREATE INDEX sec_user_version_email_idx ON public.sec_user_version USING btree "
            + "(lower((email)::text)) WHERE (email IS NOT NULL)";
        assertThat(MetaModelConsistencyChecker.lowerColumns(varchar)).containsExactly("email");
        assertThat(MetaModelConsistencyChecker.lowerColumns("CREATE UNIQUE INDEX uk ON t USING btree (lower(code))"))
            .containsExactly("code");
        assertThat(MetaModelConsistencyChecker.lowerColumns(
            "CREATE INDEX i ON t USING btree (lower((\"Mixed\")::text), lower(b))")).containsExactly("mixed", "b");

        // Another column whose name starts like it does not count.
        String alias = "CREATE INDEX i ON t USING btree (lower((email_alias)::text))";
        assertThat(MetaModelConsistencyChecker.lowerColumns(alias)).containsExactly("email_alias")
            .doesNotContain("email");
        // Nor a column merely mentioned elsewhere, nor another function.
        assertThat(MetaModelConsistencyChecker.lowerColumns(
            "CREATE INDEX lower_email_idx ON t USING btree (upper(email)) WHERE (email IS NOT NULL)")).isEmpty();
    }
}
