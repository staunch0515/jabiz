package com.jabiz.imports;

import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.TemporalRole;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportDefinitionTest {

    private static ImportDefinition.Builder<ImportDefinition.NoParams> base() {
        return ImportDefinition.define("test.base", 2)
            .file("test.import", ImportFormat.xlsx())
            .field("amount", new SemanticKind.Monetary("USD", 2), true)
            .perRow("POST", 1, (row, params) -> row)
            .permissions("test.import");
    }

    @Test
    void describesItself() {
        ImportDefinition<ImportDefinition.NoParams> definition = base()
            .field("day", new SemanticKind.Temporal(TemporalRole.EVENT_TIME), false, "Date", "Posted")
            .zone(ZoneId.of("America/Chicago"))
            .datePatterns("MM/dd/yyyy")
            .maxRows(500)
            .totals("amount")
            .permissions("test.import", "test.mapping")
            .build();
        assertThat(definition.id()).isEqualTo("test.base");
        assertThat(definition.version()).isEqualTo(2);
        assertThat(definition.filePolicy()).isEqualTo("test.import");
        assertThat(definition.format()).isInstanceOf(ImportFormat.Xlsx.class);
        assertThat(definition.fields()).extracting(ImportField::name).containsExactly("amount", "day");
        assertThat(definition.field("day").columns()).containsExactly("Date", "Posted");
        assertThat(definition.field("amount").columns()).containsExactly("amount");
        assertThat(definition.hasParams()).isFalse();
        assertThat(definition.paramsType()).isEqualTo(ImportDefinition.NoParams.class);
        assertThat(definition.externalRef()).isNull();
        assertThat(definition.onDuplicate()).isEqualTo(ImportDefinition.OnDuplicate.REJECT);
        assertThat(definition.target().process()).isEqualTo("POST");
        assertThat(definition.target().version()).isEqualTo(1);
        assertThat(definition.fileChecks()).isEmpty();
        assertThat(definition.totals()).containsExactly("amount");
        assertThat(definition.permission()).isEqualTo("test.import");
        assertThat(definition.mappingPermission()).isEqualTo("test.mapping");
        assertThat(definition.zone()).isEqualTo(ZoneId.of("America/Chicago"));
        assertThat(definition.datePatterns()).containsExactly("MM/dd/yyyy");
        assertThat(definition.maxRows()).isEqualTo(500);
        assertThat(definition.toString()).contains("test.base v2");
        assertThat(base().build().mappingPermission()).isEqualTo("test.import");
        assertThat(ImportDefinition.define("test.p", 1, String.class).file("p", ImportFormat.csv())
            .field("a", new SemanticKind.Bool(), false).perRow("P", 1, (r, p) -> p).permissions("p").build()
            .hasParams()).isTrue();
    }

    @Test
    void refusesIncompleteOrWrongDeclarations() {
        assertThatThrownBy(() -> ImportDefinition.define("Bad Id", 1).build()).hasMessageContaining("must match");
        assertThatThrownBy(() -> base().permissions(" ").build()).hasMessageContaining("default deny");
        assertThatThrownBy(() -> ImportDefinition.define("t.x", 0).build()).hasMessageContaining("version");
        assertThatThrownBy(() -> ImportDefinition.define("t.x", 1).permissions("p").build())
            .hasMessageContaining("file policy");
        assertThatThrownBy(() -> ImportDefinition.define("t.x", 1).file("p", ImportFormat.csv()).permissions("p")
            .build()).hasMessageContaining("no field");
        assertThatThrownBy(() -> ImportDefinition.define("t.x", 1).file("p", ImportFormat.csv())
            .field("a", new SemanticKind.Bool(), false).permissions("p").build())
            .hasMessageContaining("no process");
        assertThatThrownBy(() -> base().field("amount", new SemanticKind.Bool(), false))
            .hasMessageContaining("twice");
        assertThatThrownBy(() -> base().field("Bad", new SemanticKind.Bool(), false))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> base().field("x", new SemanticKind.None(), false))
            .hasMessageContaining("cannot be read");
        assertThatThrownBy(() -> base().field("t", new SemanticKind.Text(null, false), false).totals("t").build())
            .hasMessageContaining("not a monetary");
        assertThatThrownBy(() -> base().datePatterns("qqqqqq{").build()).hasMessageContaining("date pattern");
        assertThatThrownBy(() -> base().maxRows(0).build()).hasMessageContaining("maxRows");
    }

    @Test
    void rowsGiveTypedValues() {
        ImportRow row = new ImportRow(3, "line 4", Map.of("t", "x", "d", BigDecimal.ONE, "i",
            Instant.EPOCH, "b", true, "l", 5L), Set.of());
        assertThat(row.text("t")).isEqualTo("x");
        assertThat(row.text("missing")).isNull();
        assertThat(row.decimal("d")).isEqualTo(BigDecimal.ONE);
        assertThat(row.instant("i")).isEqualTo(Instant.EPOCH);
        assertThat(row.bool("b")).isTrue();
        assertThat(row.integer("l")).isEqualTo(5L);
        assertThat(row.get("t")).isEqualTo("x");
        assertThat(ImportIssue.ofFile("C", "m", null).params()).isEmpty();
        assertThat(new ImportMapping(null, null, null)).isEqualTo(ImportMapping.DEFAULT);
        assertThat(new ImportFileException("line 2", "bad").getMessage()).isEqualTo("line 2: bad");
        assertThat(new ImportFileException("C", null, "bad", null).location()).isNull();
        assertThat(ImportFormat.xlsx().headerRow(3).headerRow()).isEqualTo(3);
        assertThat(ImportFormat.xlsx().adjustable()).isTrue();
        assertThat(ImportFormat.csv().adjustable()).isTrue();
        assertThatThrownBy(() -> new ImportFormat.Xlsx(null, true, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ImportFormat.csv().skipLines(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
