package com.jabiz.entity;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RuleKinds}: exported rules are limited to the kinds clients implement (decision D15). */
class RuleKindsTest {

    private static void define(String kind, Map<String, Object> params) {
        EntityDefinition.define("Sample", eb -> {
            eb.physicalTable("t_sample");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:sample"));
            eb.field("value", f -> f.physicalColumn("f_value").rule("CODE", kind, params, v -> true));
        });
    }

    @Test
    void unknownExportedKindFailsTheBuild() {
        assertThatThrownBy(() -> define("ENUM", Map.of())).hasMessageContaining("ENUM")
            .hasMessageContaining("server-only");
        assertThatCode(() -> define("RANGE", Map.of("min", 0))).doesNotThrowAnyException();
    }

    @Test
    void serverOnlyRulesHaveNoKind() {
        assertThatCode(() -> EntityDefinition.define("Sample", eb -> {
            eb.physicalTable("t_sample");
            eb.primaryKey("id");
            eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:sample"));
            eb.field("value", f -> f.physicalColumn("f_value").rule("ANYTHING", v -> true));
        })).doesNotThrowAnyException();
    }

    @Test
    void exportedPatternsMustBePortable() {
        assertThatThrownBy(() -> define("PATTERN", Map.of("regex", "a++"))).hasMessageContaining("++");
        assertThatThrownBy(() -> define("PATTERN", Map.of("regex", "(unclosed"))).hasMessageContaining("invalid");
        assertThatCode(() -> define("PATTERN", Map.of("regex", "[a-z]+@[a-z]+\\.[a-z]{2,}")))
            .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"(?<=x)y", "(?<name>x)", "(?>x)", "(?i)x", "x*+", "x}+", "a{2}+", "\\Ax\\z", "\\Qx\\E",
        "[a-z&&[^b]]", "[a[b]]", "\\p{javaLowerCase}", "\\p{Alpha}", "\\p{Digit}", "\\h", "\\x{41}", "\\s+", "a\\-b",
        "[]a]", "[^]a]", "\\0", "\\cA", "[\\b]", "\\u12"})
    void javaOnlySyntaxIsRejected(String regex) {
        assertThatThrownBy(() -> RuleKinds.checkPortablePattern("P", regex)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"[A-Z]{2}-[0-9]{3}", "\\d+(?:\\.\\d{1,2})?", "\\p{L}+", "\\P{Lu}*", "[\\-a-z]+", "\\u00e9",
        "a(?=b)", "a(?!b)", "\\w+@\\w+\\.\\w+", "\\(\\)\\[\\]\\{\\}\\|\\/\\^\\$\\*\\+\\?", "x*?", "\\bword\\b"})
    void portableSyntaxIsAccepted(String regex) {
        assertThatCode(() -> RuleKinds.checkPortablePattern("P", regex)).doesNotThrowAnyException();
    }

    @Test
    void emptyPatternIsRejected() {
        assertThatThrownBy(() -> RuleKinds.checkPortablePattern("P", "")).hasMessageContaining("needs");
        assertThatThrownBy(() -> RuleKinds.checkPortablePattern("P", null)).hasMessageContaining("needs");
    }
}
