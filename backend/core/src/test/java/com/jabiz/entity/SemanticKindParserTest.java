package com.jabiz.entity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticKindParserTest {

    /** Parsing reads back what the metamodel export writes. */
    @Test
    void readsTheExportedShape() {
        List<SemanticKind> kinds = List.of(
            new SemanticKind.SemanticIdentity("urn:x"),
            new SemanticKind.Monetary("JPY", 0),
            new SemanticKind.Temporal(TemporalRole.VALID_FROM),
            new SemanticKind.Date(),
            new SemanticKind.Code("urn:d", List.of("A", "B")),
            new SemanticKind.Version(),
            new SemanticKind.Text(12, true),
            new SemanticKind.Text(null, false),
            new SemanticKind.Numeric(9, 4),
            new SemanticKind.Bool(),
            new SemanticKind.Reference("Order"));
        for (SemanticKind kind : kinds) {
            assertThat(SemanticKindParser.parse(MetaModelExporter.kindToJson(kind))).isEqualTo(kind);
        }
    }

    @Test
    void customKindsTakeTheirParameters() {
        assertThat(SemanticKindParser.parse(Map.of("type", "custom", "kindId", "geo.h3", "params", Map.of("resolution", 8))))
            .isEqualTo(new SemanticKind.Custom("geo.h3", Map.of("resolution", 8)));
        assertThat(SemanticKindParser.parse(Map.of("type", "custom", "kindId", "k")))
            .isEqualTo(new SemanticKind.Custom("k", Map.of()));
        assertThat(SemanticKindParser.parse(Map.of("type", "temporal", "role", "event_time")))
            .isEqualTo(new SemanticKind.Temporal(TemporalRole.EVENT_TIME));
    }

    @Test
    void malformedKindsAreRejected() {
        assertThatThrownBy(() -> SemanticKindParser.parse(null)).hasMessageContaining("must not be empty");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of())).hasMessageContaining("needs 'type'");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "none"))).hasMessageContaining("unknown kind");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "temporal", "role", "SOON")))
            .hasMessageContaining("unknown temporal role");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "numeric", "precision", 1.5, "scale", 0)))
            .hasMessageContaining("must be an integer");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "code", "dictUrn", "u", "allowedValues", "A")))
            .hasMessageContaining("must be a list");
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "custom", "kindId", "k", "params", 1)))
            .hasMessageContaining("must be a map");
        java.util.Map<String, Object> withNull = new java.util.HashMap<>();
        withNull.put("unit", null);
        assertThatThrownBy(() -> SemanticKindParser.parse(Map.of("type", "custom", "kindId", "k", "params", withNull)))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'unit' has no value");
    }
}
