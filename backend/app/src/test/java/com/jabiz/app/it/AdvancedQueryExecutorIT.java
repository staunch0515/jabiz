package com.jabiz.app.it;

import com.jabiz.app.LogisticsAnalyticsQueries;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.SemanticKind;
import com.jabiz.ext.geo.DimensionType;
import com.jabiz.ext.geo.GeoKinds;
import com.jabiz.query.custom.SemanticRow;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs the sample multi-entity query {@link LogisticsAnalyticsQueries#TOKYO_PORT_WAYBILL_CUSTOMS_AUDIT}. */
class AdvancedQueryExecutorIT extends PostgresIntegrationTest {

    private static final long PORT_CELL = 0x882f516a23ffffL;

    @Autowired
    AdvancedQueryExecutor executor;

    @Autowired
    DatasetRegistry datasets;

    private DatasetDefinition waybills;

    @BeforeEach
    void seed() {
        waybills = datasets.findById("urn:jabiz:dataset:default:WaybillTracking").orElseThrow();
        execute("DELETE FROM t_customs_declaration");
        execute("DELETE FROM t_legacy_waybill_2026");
        waybill("WB-1", "CUSTOMS_CLEARED", 5000, START.minus(Duration.ofDays(2)));
        waybill("WB-2", "DELIVERED", 800, START.minus(Duration.ofDays(3)));
        waybill("WB-3", "IN_TRANSIT", 9000, START.minus(Duration.ofDays(1)));
        waybill("WB-4", "DELIVERED", 7000, START.minus(Duration.ofDays(10)));
        waybill("WB-5", "CUSTOMS_CLEARED", 6000, START.minus(Duration.ofDays(4)));
        declaration("D-1", "WB-1", 300);
        declaration("D-2", "WB-2", 50);
        declaration("D-3", "WB-3", 900);
        declaration("D-4", "WB-4", 700);
        // WB-5 has no declaration and is excluded by the inner join.
    }

    private static void waybill(String id, String status, long freight, Instant shipped) {
        execute("""
            INSERT INTO t_legacy_waybill_2026
                (f_wb_sn, f_charge_amt, f_gross_wt, f_shipped_timestamp, f_current_h3_cell, f_status_code)
            VALUES (?, ?, ?, ?, ?, ?)""",
            id, BigDecimal.valueOf(freight), new BigDecimal("12.500"), Timestamp.from(shipped), PORT_CELL, status);
    }

    private static void declaration(String id, String waybill, long duty) {
        execute("INSERT INTO t_customs_declaration (f_decl_no, f_wb_ref_sn, f_duty_amt) VALUES (?, ?, ?)",
            id, waybill, BigDecimal.valueOf(duty));
    }

    private List<SemanticRow> run(Map<String, Object> params) {
        return asTestRequest(executor.execute(waybills, LogisticsAnalyticsQueries.TOKYO_PORT_WAYBILL_CUSTOMS_AUDIT, params)
            .collectList()).block();
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void filtersJoinsAndOrdersByShippedTimeDescending() {
        List<SemanticRow> rows = run(params(
            "minFreight", 1000, "allowedStatuses", List.of("CUSTOMS_CLEARED", "DELIVERED")));

        assertThat(rows).extracting(r -> r.getRaw("waybillSn")).containsExactly("WB-1", "WB-4");
        assertThat(rows).extracting(r -> r.getRaw("declarationNo")).containsExactly("D-1", "D-4");
    }

    @Test
    void resultColumnsCarryTheirSemanticKindsAndCanonicalTypes() {
        SemanticRow row = run(params("minFreight", "4000", "allowedStatuses", List.of("CUSTOMS_CLEARED"))).get(0);

        assertThat(row.getAllColumns().keySet()).containsExactly(
            "waybillSn", "finalFreight", "clearedWeightKg", "customsPortCell", "declarationNo", "dutyPaid");
        assertThat(row.get("finalFreight").kind()).isEqualTo(new SemanticKind.Monetary("JPY", 0));
        assertThat(row.get("finalFreight").as(BigDecimal.class)).isEqualByComparingTo("5000");
        assertThat(row.get("clearedWeightKg").kind())
            .isEqualTo(GeoKinds.quantity(DimensionType.MASS, "urn:unit:si:kilogram"));
        assertThat(row.get("clearedWeightKg").as(BigDecimal.class)).isEqualByComparingTo("12.5");
        assertThat(row.get("customsPortCell").as(Long.class)).isEqualTo(PORT_CELL);
        assertThat(row.get("dutyPaid").as(BigDecimal.class)).isEqualByComparingTo("300");
    }

    @Test
    void optionalParameterNarrowsTheResult() {
        List<SemanticRow> rows = run(params(
            "minFreight", 1000,
            "allowedStatuses", List.of("CUSTOMS_CLEARED", "DELIVERED"),
            "shippedAfter", START.minus(Duration.ofDays(5)).toString()));

        assertThat(rows).extracting(r -> r.getRaw("waybillSn")).containsExactly("WB-1");
    }

    @Test
    void missingRequiredParameterIsRejected() {
        assertThatThrownBy(() -> run(params("allowedStatuses", List.of("DELIVERED"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required query parameter: minFreight");
    }

    @Test
    void parameterValuesAreCheckedAgainstTheirSemanticKind() {
        assertThatThrownBy(() -> run(params("minFreight", 1000, "allowedStatuses", List.of("LOST"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Parameter [allowedStatuses]");
        assertThatThrownBy(() -> run(params("minFreight", "a lot", "allowedStatuses", List.of("DELIVERED"))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Parameter [minFreight]");
    }
}
