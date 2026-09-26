package com.jabiz.runtime.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The JDBC form of a rendered template and the mapping of server error positions back to it. */
class SqlTemplatePrecompileCheckTest {

    @Test
    void parametersBecomeQuestionMarksAndOperatorsAreEscaped() {
        SqlTemplatePrecompileCheck.Positional positional = SqlTemplatePrecompileCheck.Positional.of(
            "SELECT a ? 'k', ':x ?' FROM t WHERE b = :first AND c ?| :second::text[]");

        assertThat(positional.sql())
            .isEqualTo("SELECT a ?? 'k', ':x ?' FROM t WHERE b = ? AND c ??| ?::text[]");
        assertThat(positional.names()).containsExactly("first", "second");
    }

    @Test
    void serverPositionsAreMappedThroughTheNumberedParameters() {
        StringBuilder rendered = new StringBuilder("SELECT 1 WHERE");
        for (int i = 1; i <= 11; i++) {
            rendered.append(" x = :parameter").append(i).append(" AND");
        }
        rendered.append(" oops");
        String text = rendered.toString();
        // What the server receives: :parameterN became $N.
        String server = text.replaceAll(":parameter(\\d+)", "\\$$1");

        SqlTemplatePrecompileCheck.Positional positional = SqlTemplatePrecompileCheck.Positional.of(text);

        assertThat(positional.renderedOffsetOfServer(server.indexOf("oops"))).isEqualTo(text.indexOf("oops"));
        assertThat(positional.renderedOffsetOfServer(3)).isEqualTo(3);
    }
}
