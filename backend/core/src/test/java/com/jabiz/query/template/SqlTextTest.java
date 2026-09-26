package com.jabiz.query.template;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SqlTextTest {

    @Test
    void maskBlanksLiteralsQuotedNamesAndCommentsButKeepsOffsets() {
        String sql = "SELECT 'a :x {{E}}' AS \"q :y\", $$ :z $$, $tag$ :w $tag$ -- :c\n/* :d /* nested */ :e */ :p";

        String masked = SqlText.mask(sql);

        assertThat(masked).hasSameSizeAs(sql);
        assertThat(SqlText.parameters(masked)).extracting(SqlText.ParameterRef::name).containsExactly("p");
        assertThat(masked).contains("\n").doesNotContain(":x", ":y", ":z", ":w", ":c", ":d", ":e", "{{E}}");
    }

    @Test
    void doubledQuotesStayInsideTheLiteral() {
        assertThat(SqlText.parameters(SqlText.mask("SELECT 'it''s :no', :yes"))).extracting(SqlText.ParameterRef::name)
            .containsExactly("yes");
    }

    @Test
    void castsAndSliceBoundsAreNotParameters() {
        String masked = SqlText.mask("SELECT x::text, a[i:j], :real, CAST(:other AS int)");

        assertThat(SqlText.parameters(masked)).extracting(SqlText.ParameterRef::name).containsExactly("real", "other");
    }

    @Test
    void dollarSignsInsideNamesAreNotQuotes() {
        String masked = SqlText.mask("SELECT a$b, :p");

        assertThat(SqlText.parameters(masked)).extracting(SqlText.ParameterRef::name).containsExactly("p");
    }

    @Test
    void unterminatedLiteralsAndCommentsRunToTheEnd() {
        assertThat(SqlText.parameters(SqlText.mask("SELECT ':p"))).isEmpty();
        assertThat(SqlText.parameters(SqlText.mask("SELECT 1 /* :p"))).isEmpty();
        assertThat(SqlText.parameters(SqlText.mask("SELECT $$ :p"))).isEmpty();
    }

    @Test
    void placeholdersAreFoundWithTheirRanges() {
        String sql = "SELECT e.{{ Entity.field }} FROM {{Entity}} e";

        assertThat(SqlText.placeholders(SqlText.mask(sql)))
            .containsExactly(new SqlText.Placeholder(9, 27, "Entity", "field"),
                new SqlText.Placeholder(33, 43, "Entity", null));
    }

    @Test
    void inListsOfParametersAreFound() {
        assertThat(SqlText.inListParameters(SqlText.mask("WHERE a in ( :xs ) AND b IN (1, 2) AND c IN (:ys, :z)")))
            .extracting(SqlText.ParameterRef::name).containsExactly("xs", "ys");
    }

    @Test
    void onlyTopLevelKeywordsCount() {
        String masked = SqlText.mask("SELECT * FROM (SELECT 1 LIMIT 1) s WHERE limitless LIMIT 5 OFFSET 2");

        assertThat(SqlText.topLevelKeywords(masked, Set.of("LIMIT", "OFFSET")))
            .containsExactly(masked.indexOf("LIMIT 5"), masked.indexOf("OFFSET"));
    }

    @Test
    void wordsSkipParametersAndPartsOfOtherWords() {
        assertThat(SqlText.words(SqlText.mask("SELECT t_a, 1x FROM :t_b")))
            .extracting(SqlText.ParameterRef::name).containsExactly("SELECT", "t_a", "FROM");
    }
}
