package com.jabiz.app.it.temporal;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.PhysicalQueryPlan;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.QueryPredicate;
import com.jabiz.query.TimeSlice;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Differential test of decision D29: over random version histories (updates, tombstones, versions scheduled after
 * the read, versions recorded after {@code knownAt}), a read whose conditions on immutable fields also restrict the
 * versions read returns exactly what the plain read of 04 section 5.1 returns: the latest version in effect, then
 * tombstones, scope and conditions outside. A write-once table read directly matches the plain read as well.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TemporalPushdownIT extends PostgresIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    /** grp never changes (immutable); memo and qty do. */
    private static final EntityDefinition ITEM = EntityDefinition.define("PdItem", eb -> {
        eb.physicalTable("pd_item_version");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("item_id").immutable(true).asSemanticIdentity("urn:test:pd-item"));
        eb.field("grp", f -> f.physicalColumn("grp").immutable(true).asText(10));
        eb.field("memo", f -> f.physicalColumn("memo").asText(10));
        eb.field("qty", f -> f.physicalColumn("qty").asNumeric(3, 0));
        eb.temporal(t -> t.allowScheduled(true));
    });

    private static final EntityDefinition ONCE = EntityDefinition.define("PdOnce", eb -> {
        eb.physicalTable("pd_once_version");
        eb.primaryKey("itemId");
        eb.field("itemId", f -> f.physicalColumn("item_id").immutable(true).asSemanticIdentity("urn:test:pd-once"));
        eb.field("grp", f -> f.physicalColumn("grp").immutable(true).asText(10));
        eb.field("memo", f -> f.physicalColumn("memo").asText(10));
        eb.field("qty", f -> f.physicalColumn("qty").asNumeric(3, 0));
        eb.temporal(t -> t.writeOnce());
    });

    /** Scoped on a changeable field: the scope must keep applying to the version in effect only. */
    private static final DatasetDefinition SCOPED = DatasetDefinition.define("urn:jabiz:dataset:test:PdItem", d -> d
        .targetEntityType("PdItem").scope(s -> s.fixed("memo", "a")).storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition PLAIN = DatasetDefinition.define("urn:jabiz:dataset:test:PdItemAll", d -> d
        .targetEntityType("PdItem").storage(s -> s.connectionPoolRef("default")));
    private static final DatasetDefinition ONCE_DATASET = DatasetDefinition.define("urn:jabiz:dataset:test:PdOnce",
        d -> d.targetEntityType("PdOnce").storage(s -> s.connectionPoolRef("default")));

    private static final String[] GROUPS = {"g1", "g2", "g3", "g4", "g5"};
    private static final String[] MEMOS = {"a", "b", "c"};

    private final QueryCompiler compiler = new QueryCompiler();
    private final Random random = new Random(29);

    private static boolean made;

    /** The histories, made once per class in the class's own schema (known only once the context is up). */
    @BeforeEach
    void histories() {
        if (made) {
            return;
        }
        made = true;
        for (String table : List.of("pd_item_version", "pd_once_version")) {
            execute("CREATE TABLE " + table + " (row_id bigserial PRIMARY KEY, item_id uuid NOT NULL, "
                + "version_no int NOT NULL, effect_start_time timestamptz NOT NULL, created_time timestamptz NOT NULL, "
                + "process_seq_id bigint NOT NULL, is_deleted boolean NOT NULL, grp varchar(10), memo varchar(10), "
                + "qty numeric(3,0))");
        }
        Random histories = new Random(14);
        for (int item = 0; item < 400; item++) {
            UUID id = UUID.randomUUID();
            String grp = GROUPS[histories.nextInt(GROUPS.length)];
            int versions = 1 + histories.nextInt(6);
            Instant recorded = T0.plus(Duration.ofHours(histories.nextInt(48)));
            for (int v = 1; v <= versions; v++) {
                recorded = recorded.plus(Duration.ofHours(1 + histories.nextInt(24)));
                // Mostly in effect when recorded, sometimes back-dated or scheduled.
                Instant effective = switch (histories.nextInt(5)) {
                    case 0 -> recorded.minus(Duration.ofHours(histories.nextInt(72)));
                    case 1 -> recorded.plus(Duration.ofHours(histories.nextInt(72)));
                    default -> recorded;
                };
                // A tombstone keeps the immutable values, as the platform writes it (VersionPlanner).
                boolean deleted = v > 1 && histories.nextInt(6) == 0;
                execute("INSERT INTO pd_item_version (item_id, version_no, effect_start_time, created_time, "
                    + "process_seq_id, is_deleted, grp, memo, qty) VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)",
                    id, v, Timestamp.from(effective), Timestamp.from(recorded), deleted, grp,
                    MEMOS[histories.nextInt(MEMOS.length)], BigDecimal.valueOf(histories.nextInt(10)));
            }
            execute("INSERT INTO pd_once_version (item_id, version_no, effect_start_time, created_time, "
                + "process_seq_id, is_deleted, grp, memo, qty) VALUES (?, 1, ?, ?, 1, false, ?, ?, ?)",
                id, Timestamp.from(recorded), Timestamp.from(recorded), grp, MEMOS[histories.nextInt(MEMOS.length)],
                BigDecimal.valueOf(histories.nextInt(10)));
        }
    }

    @Test
    void readsWithConditionsPushedDownMatchThePlainRead() {
        int nonEmpty = 0;
        for (int round = 0; round < 300; round++) {
            DatasetDefinition dataset = random.nextBoolean() ? SCOPED : PLAIN;
            QueryPredicate where = predicate(0);
            TimeSlice slice = slice();
            PhysicalQueryPlan plan = compiler.compile(dataset, ITEM, EntityQuery.builder().where(where).limit(1000)
                .build(), dataset == SCOPED ? Map.of("memo", "a") : Map.of(), slice);
            Set<String> actual = ids("SELECT item_id::text AS id FROM " + plan.source() + " WHERE " + plan.whereClause(),
                plan.bindParams());

            Set<String> expected = ids(reference("pd_item_version", dataset == SCOPED, where, slice), Map.of());
            assertThat(actual).as("round %d: %s at %s", round, where, slice).isEqualTo(expected);
            if (!expected.isEmpty()) {
                nonEmpty++;
            }
        }
        // The comparison is meaningful: most rounds find something.
        assertThat(nonEmpty).isGreaterThan(150);
    }

    @Test
    void aWriteOnceTableReadDirectlyMatchesThePlainRead() {
        for (int round = 0; round < 100; round++) {
            QueryPredicate where = predicate(0);
            TimeSlice slice = slice();
            PhysicalQueryPlan plan = compiler.compile(ONCE_DATASET, ONCE, EntityQuery.builder().where(where)
                .limit(1000).build(), Map.of(), slice);
            assertThat(plan.source()).doesNotContain("DISTINCT ON");
            Set<String> actual = ids("SELECT item_id::text AS id FROM " + plan.source() + " WHERE " + plan.whereClause(),
                plan.bindParams());
            assertThat(actual).as("round %d", round)
                .isEqualTo(ids(reference("pd_once_version", false, where, slice), Map.of()));
        }
    }

    // ---- random reads ----------------------------------------------------------------------------------------------

    private TimeSlice slice() {
        Instant asOf = T0.plus(Duration.ofHours(random.nextInt(24 * 12)));
        Instant knownAt = random.nextInt(3) == 0 ? T0.plus(Duration.ofHours(random.nextInt(24 * 12))) : null;
        return new TimeSlice(asOf, knownAt);
    }

    private QueryPredicate predicate(int depth) {
        int kind = random.nextInt(depth < 2 ? 9 : 7);
        return switch (kind) {
            case 0 -> new QueryPredicate.Eq("grp", GROUPS[random.nextInt(GROUPS.length)]);
            case 1 -> new QueryPredicate.In("grp", List.of(GROUPS[random.nextInt(5)], GROUPS[random.nextInt(5)]));
            case 2 -> new QueryPredicate.Ne("grp", GROUPS[random.nextInt(GROUPS.length)]);
            case 3 -> new QueryPredicate.Eq("memo", MEMOS[random.nextInt(MEMOS.length)]);
            case 4 -> new QueryPredicate.Gt("qty", random.nextInt(10));
            case 5 -> new QueryPredicate.Lte("qty", random.nextInt(10));
            case 6 -> new QueryPredicate.Like("grp", "g" + (1 + random.nextInt(5)) + "%");
            case 7 -> new QueryPredicate.And(List.of(predicate(depth + 1), predicate(depth + 1)));
            default -> new QueryPredicate.Or(List.of(predicate(depth + 1), predicate(depth + 1)));
        };
    }

    // ---- the plain read of 04 section 5.1, written out ----------------------------------------------------------

    private static String reference(String table, boolean scoped, QueryPredicate where, TimeSlice slice) {
        String time = "effect_start_time <= '" + slice.asOf() + "'"
            + (slice.knownAt() == null ? "" : " AND created_time <= '" + slice.knownAt() + "'");
        return "SELECT item_id::text AS id FROM (SELECT DISTINCT ON (item_id) * FROM " + table + " WHERE " + time
            + " ORDER BY item_id, effect_start_time DESC, version_no DESC) v WHERE NOT is_deleted"
            + (scoped ? " AND memo = 'a'" : "") + " AND " + sql(where);
    }

    private static String sql(QueryPredicate predicate) {
        return switch (predicate) {
            case QueryPredicate.Eq p -> p.field() + " = " + literal(p.value());
            case QueryPredicate.Ne p -> p.field() + " <> " + literal(p.value());
            case QueryPredicate.Gt p -> p.field() + " > " + literal(p.value());
            case QueryPredicate.Lte p -> p.field() + " <= " + literal(p.value());
            case QueryPredicate.In p -> p.field() + " IN (" + String.join(", ", p.values().stream()
                .map(TemporalPushdownIT::literal).toList()) + ")";
            case QueryPredicate.Like p -> p.field() + " LIKE " + literal(p.pattern());
            case QueryPredicate.And p -> "(" + sql(p.predicates().get(0)) + " AND " + sql(p.predicates().get(1)) + ")";
            case QueryPredicate.Or p -> "(" + sql(p.predicates().get(0)) + " OR " + sql(p.predicates().get(1)) + ")";
            default -> throw new IllegalArgumentException(predicate.toString());
        };
    }

    private static String literal(Object value) {
        return value instanceof Number ? value.toString() : "'" + value + "'";
    }

    // ---- running compiled SQL over JDBC ---------------------------------------------------------------------------

    private static final Pattern PARAM = Pattern.compile("(?<!:):(\\w+)");

    private static Set<String> ids(String sql, Map<String, BoundValue> params) {
        List<Object> values = new ArrayList<>();
        StringBuilder jdbc = new StringBuilder();
        // The reference reads carry their values as literals (times contain colons): nothing to bind.
        Matcher m = PARAM.matcher(params.isEmpty() ? "" : sql);
        while (m.find()) {
            Object value = params.get(m.group(1)).value();
            if (value instanceof Collection<?> list) {
                m.appendReplacement(jdbc, String.join(", ", list.stream().map(x -> "?").toList()));
                list.forEach(x -> values.add(jdbcValue(x)));
            } else {
                m.appendReplacement(jdbc, "?");
                values.add(jdbcValue(value));
            }
        }
        m.appendTail(jdbc);
        if (params.isEmpty()) {
            jdbc.append(sql);
        }
        Set<String> ids = new TreeSet<>();
        for (Map<String, Object> row : query(jdbc.toString(), values.toArray())) {
            ids.add((String) row.get("id"));
        }
        return ids;
    }

    private static Object jdbcValue(Object value) {
        return value instanceof Instant instant ? Timestamp.from(instant) : value;
    }
}
