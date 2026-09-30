package com.jabiz.query;

import com.jabiz.context.DataPeriod;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.DatasetScope;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Masked fields and data periods in generated SQL (docs/design/10-security.md sections 13.1 and 13.2): masked columns
 * are replaced inside the template expression, so no condition or sort sees the plain value; a period is a range on
 * the time field, or on the referenced instance's.
 */
class AccessControlCompilerTest {

    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2027-01-01T00:00:00Z");

    private static final EntityDefinition SUPPLIER = EntityDefinition.define("Supplier", eb -> {
        eb.physicalTable("t_supplier");
        eb.primaryKey("supplierId");
        eb.field("supplierId", f -> f.physicalColumn("supplier_id").asSemanticIdentity("urn:test:supplier"));
        eb.field("name", f -> f.physicalColumn("name").asText(100));
        eb.field("iban", f -> f.physicalColumn("iban").asText(34).masked("supplier.bank", MaskStyle.LAST4));
    });

    private static final EntityDefinition TRANSACTION = EntityDefinition.define("Txn", eb -> {
        eb.physicalTable("t_txn");
        eb.primaryKey("txnId");
        eb.field("txnId", f -> f.physicalColumn("txn_id").asSemanticIdentity("urn:test:txn"));
        eb.field("bookingTime", f -> f.physicalColumn("booking_time").immutable(true)
            .asTemporal(com.jabiz.entity.TemporalRole.EVENT_TIME));
        eb.temporal(t -> t.allowScheduled(false));
    });

    private static final EntityDefinition LINE = EntityDefinition.define("Line", eb -> {
        eb.physicalTable("t_line");
        eb.primaryKey("lineId");
        eb.field("lineId", f -> f.physicalColumn("line_id").asSemanticIdentity("urn:test:line"));
        eb.field("txnId", f -> f.physicalColumn("txn_id").asReference("Txn"));
    });

    private static final DatasetDefinition SUPPLIERS = DatasetDefinition.define("urn:test:Supplier", d -> d
        .targetEntityType("Supplier").storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition TRANSACTIONS = DatasetDefinition.define("urn:test:Txn", d -> d
        .targetEntityType("Txn").scope(s -> s.withinDataPeriod("bookingTime"))
        .storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition LINES = DatasetDefinition.define("urn:test:Line", d -> d
        .targetEntityType("Line").scope(s -> s.withinDataPeriod("txnId", "bookingTime"))
        .storage(s -> s.connectionPoolRef("default")));

    private final QueryCompiler compiler = new QueryCompiler(name -> Optional.ofNullable(Map.of("Txn", TRANSACTION,
        "Supplier", SUPPLIER, "Line", LINE).get(name)));

    @Test
    void maskedColumnsAreReplacedInsideTheTemplateExpression() {
        QueryCompiler.Binder binder = new QueryCompiler.Binder("s");
        assertThat(compiler.templateExpression(SUPPLIERS, SUPPLIER, Map.of(), null, binder))
            .isEqualTo("(SELECT supplier_id, name, " + MaskStyle.LAST4.sql("iban") + " AS iban FROM t_supplier)");
        // Holders of the permission read it as it is: the table itself.
        assertThat(compiler.templateExpression(SUPPLIERS, SUPPLIER, Map.of(), null, binder, Set.of("iban")))
            .isEqualTo("t_supplier");
    }

    @Test
    void temporalEntitiesKeepTheirRowIdentity() {
        EntityDefinition price = EntityDefinition.define("Price", eb -> {
            eb.physicalTable("t_price");
            eb.primaryKey("priceId");
            eb.field("priceId", f -> f.physicalColumn("price_id").asSemanticIdentity("urn:test:price"));
            eb.field("note", f -> f.physicalColumn("note").asText(50).masked("p", MaskStyle.ALL));
            eb.temporal(t -> t.allowScheduled(false));
        });
        DatasetDefinition prices = DatasetDefinition.define("urn:test:Price", d -> d.targetEntityType("Price")
            .storage(s -> s.connectionPoolRef("default")));
        String expression = compiler.templateExpression(prices, price, Map.of(), TimeSlice.asOf(FROM),
            new QueryCompiler.Binder("s"));
        assertThat(expression).startsWith("(SELECT price_id, ").contains(MaskStyle.ALL.sql("note") + " AS note")
            .contains(", row_id FROM (SELECT DISTINCT ON (price_id) * FROM t_price").endsWith(" WHERE NOT is_deleted)");
    }

    @Test
    void aPeriodIsARangeOnTheTimeFieldOrTheReferencedOnes() {
        DatasetScope.PeriodCondition period = new DatasetScope.PeriodCondition(new DataPeriod(FROM, TO), null);
        QueryCompiler.Binder binder = new QueryCompiler.Binder("p");
        assertThat(compiler.scopeCondition(TRANSACTIONS, TRANSACTION, Map.of("bookingTime", period), binder))
            .isEqualTo("(booking_time >= :p0 AND booking_time < :p1)");
        assertThat(binder.params().get("p0").value()).isEqualTo(FROM);
        assertThat(binder.params().get("p1").value()).isEqualTo(TO);

        QueryCompiler.Binder open = new QueryCompiler.Binder("q");
        assertThat(compiler.scopeCondition(LINES, LINE, Map.of("txnId", new DatasetScope.PeriodCondition(
                new DataPeriod(null, TO), "bookingTime")), open))
            .isEqualTo("txn_id IN (SELECT txn_id FROM t_txn WHERE (booking_time < :q0))");
    }

    @Test
    void aPeriodIsTakenFromTheContextAndItsAbsenceMeansNoLimit() {
        RequestContext limited = new RequestContext("auditor", null, Locale.ENGLISH, "r", Set.of(), Set.of(), null,
            new DataPeriod(FROM, TO));
        RequestContext unlimited = new RequestContext("clerk", null, Locale.ENGLISH, "r", Set.of(), Set.of());
        assertThat(TRANSACTIONS.scope().resolve(limited)).containsExactly(Map.entry("bookingTime",
            new DatasetScope.PeriodCondition(new DataPeriod(FROM, TO), null)));
        assertThat(TRANSACTIONS.scope().resolve(unlimited)).isEmpty();
        assertThat(TRANSACTIONS.scope().isDynamic()).isTrue();
        assertThat(LINES.scope().resolve(limited).get("txnId"))
            .isEqualTo(new DatasetScope.PeriodCondition(new DataPeriod(FROM, TO), "bookingTime"));
    }
}
