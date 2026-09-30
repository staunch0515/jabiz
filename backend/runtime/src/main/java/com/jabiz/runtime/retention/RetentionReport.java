package com.jabiz.runtime.retention;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.entity.TemporalSpec;
import com.jabiz.query.BoundValue;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What is past its retention (docs/design/21-audit-retention.md section 3.4): for each policy, how many current
 * entries there are, how many are past their retention today, and how many of those a legal hold keeps. Deleting or
 * anonymizing them is not the platform's (decision D27, item 5). Names come from the metamodel and are checked; values
 * are bound.
 */
@Component
public class RetentionReport {

    /**
     * @param expiredThrough entries dated on or before it are past their retention
     * @param entries        current entries (temporal: current versions that are not deleted)
     * @param expired        entries past their retention
     * @param held           of those, entries under a legal hold
     */
    public record PolicyStatus(String entity, String keep, String from, boolean fromFiscalYearEnd,
        LocalDate expiredThrough, long entries, long expired, long held) {}

    public record Report(LocalDate today, int fiscalYearEnd, List<PolicyStatus> policies) {}

    private final RetentionPolicies policies;
    private final EntityDefinitionRegistry entities;
    private final DeletionGuard guard;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final Clock clock;

    public RetentionReport(RetentionPolicies policies, EntityDefinitionRegistry entities, DeletionGuard guard,
        StorageAdapterRegistry storages, @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        Clock clock) {
        this.policies = policies;
        this.entities = entities;
        this.guard = guard;
        this.storages = storages;
        this.poolRef = poolRef;
        this.clock = clock;
    }

    public Mono<Report> report() {
        StorageEngine engine = storages.getEngine(poolRef);
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        return Flux.fromIterable(policies.all())
            .filter(RetentionPolicy::complete)
            .filter(policy -> entities.find(policy.entity()).isPresent())
            .concatMap(policy -> status(engine, policy, entities.getOrThrow(policy.entity()), now, today))
            .collectList()
            .map(list -> new Report(today, policies.fiscalYearEnd().getValue(), list));
    }

    private Mono<PolicyStatus> status(StorageEngine engine, RetentionPolicy policy, EntityDefinition def,
        Instant now, LocalDate today) {
        LocalDate through = policy.expiredThrough(today, policies.fiscalYearEnd());
        return guard.holds(engine, def.name).collectList().flatMap(holds -> {
            Map<String, BoundValue> params = new LinkedHashMap<>();
            params.put("bound", BoundValue.of(through.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)));
            String pk = "r." + column(def.primaryKeyColumn());
            List<String> covered = new ArrayList<>();
            for (int i = 0; i < holds.size(); i++) {
                var hold = holds.get(i).hold();
                if (!hold.ids().isEmpty()) {
                    covered.add(pk + "::text = ANY(:ids" + i + ")");
                    params.put("ids" + i, BoundValue.of(hold.ids().toArray(String[]::new)));
                } else if (def.fields.containsKey(hold.field()) && hold.value() != null) {
                    covered.add("r." + column(def.fields.get(hold.field()).physicalColumn()) + "::text = :value" + i);
                    params.put("value" + i, BoundValue.of(hold.value()));
                }
            }
            String date = "r." + column(def.fields.get(policy.from()).physicalColumn());
            String held = covered.isEmpty() ? "FALSE" : "(" + String.join(" OR ", covered) + ")";
            String sql = "SELECT count(*) AS entries, count(*) FILTER (WHERE " + date + " < :bound) AS expired,"
                + " count(*) FILTER (WHERE " + date + " < :bound AND " + held + ") AS held FROM "
                + relation(def, params, now);
            return engine.select(sql, params).next().map(row -> new PolicyStatus(policy.entity(),
                policy.keep().toString(), policy.from(), policy.fromFiscalYearEnd(), through,
                Rows.longValue(row.get("entries")), Rows.longValue(row.get("expired")),
                Rows.longValue(row.get("held"))));
        });
    }

    /** The entity's current entries as {@code r}: a temporal entity's current versions that are not deleted. */
    private static String relation(EntityDefinition def, Map<String, BoundValue> params, Instant now) {
        String table = column(def.physicalTable);
        if (!def.temporal) {
            return table + " r";
        }
        params.put("now", BoundValue.of(now));
        String pk = column(def.primaryKeyColumn());
        String effective = column(system(def, TemporalSpec.EFFECT_START_TIME));
        String version = column(system(def, TemporalSpec.VERSION_NO));
        String deleted = column(system(def, TemporalSpec.DELETED));
        return "(SELECT DISTINCT ON (" + pk + ") * FROM " + table + " WHERE " + effective + " <= :now ORDER BY " + pk
            + ", " + effective + " DESC, " + version + " DESC) r WHERE NOT r." + deleted;
    }

    private static String system(EntityDefinition def, String field) {
        FieldDefinition definition = def.fields.get(field);
        return definition != null ? definition.physicalColumn() : TemporalSpec.DEFAULT_COLUMNS.get(field);
    }

    private static String column(String name) {
        return SqlIdentifiers.require(name);
    }
}
