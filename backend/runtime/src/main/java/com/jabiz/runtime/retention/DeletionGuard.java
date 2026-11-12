package com.jabiz.runtime.retention;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.retention.LegalHold;
import com.jabiz.retention.RetentionPolicy;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Refuses the deletion of entries that must be kept (docs/design/21-audit-retention.md section 3.2): within their
 * entity's retention period, or under a legal hold in force. Asked by every deletion - a plain row's removal and a
 * temporal entry's tombstone - in the deleting transaction, so a hold placed concurrently is seen by the next one.
 */
@Component
public class DeletionGuard {

    /** Current versions of the holds on an entity type that are in force. */
    private static final String HOLDS = """
        SELECT hold_id::text AS hold_id, hold_name, entity_ids, match_field, match_value FROM (
            SELECT DISTINCT ON (hold_id) * FROM sys_legal_hold_version
            WHERE entity_type = :entity AND effect_start_time <= :now
            ORDER BY hold_id, effect_start_time DESC, version_no DESC) AS h
        WHERE NOT h.is_deleted AND h.status = 'ACTIVE'""";

    /** A hold and its name, as the refusal names it. */
    record NamedHold(LegalHold hold, String name) {}

    private final RetentionPolicies policies;
    private final Clock clock;
    private final JsonMapper json;

    public DeletionGuard(RetentionPolicies policies, Clock clock, JsonMapper json) {
        this.policies = policies;
        this.clock = clock;
        this.json = json;
    }

    /** Fails with 422 {@code RETENTION_ACTIVE} or {@code LEGAL_HOLD} when the entry must be kept. */
    public Mono<Void> check(StorageEngine engine, EntityDefinition def, Object id, Map<String, ?> attributes) {
        return refusal(engine, def, id, attributes)
            .flatMap(violation -> Mono.<Void>error(new BusinessRuleViolationException(violation)));
    }

    /** Why the entry must be kept, or empty when it may be deleted. */
    public Mono<Violation> refusal(StorageEngine engine, EntityDefinition def, Object id, Map<String, ?> attributes) {
        return Mono.defer(() -> {
            Violation retained = retention(def, attributes);
            if (retained != null) {
                return Mono.just(retained);
            }
            return holds(engine, def.name)
                .filter(named -> named.hold().covers(def.name, id, attributes))
                .next()
                .map(named -> held(def, id, named));
        });
    }

    /**
     * The holds in force on an entity type, read once for several deletions in one transaction (ROADMAP 17zz21): what
     * {@link #check(StorageEngine, EntityDefinition, Object, Map, Holds)} then asks instead of reading them again.
     */
    public Mono<Holds> holdsInForce(StorageEngine engine, String entity) {
        return holds(engine, entity).collectList().map(list -> new Holds(entity, list));
    }

    /** As {@link #check(StorageEngine, EntityDefinition, Object, Map)}, with the holds read ahead. */
    public Mono<Void> check(StorageEngine engine, EntityDefinition def, Object id, Map<String, ?> attributes,
        Holds holds) {
        if (!holds.entity.equals(def.name)) {
            return check(engine, def, id, attributes);
        }
        return Mono.defer(() -> {
            Violation refused = retention(def, attributes);
            if (refused == null) {
                refused = holds.list.stream().filter(named -> named.hold().covers(def.name, id, attributes))
                    .findFirst().map(named -> held(def, id, named)).orElse(null);
            }
            return refused == null ? Mono.<Void>empty() : Mono.<Void>error(new BusinessRuleViolationException(refused));
        });
    }

    /** The holds in force on one entity type, in the order a deletion asks them. */
    public static final class Holds {
        private final String entity;
        private final List<NamedHold> list;

        private Holds(String entity, List<NamedHold> list) {
            this.entity = entity;
            this.list = List.copyOf(list);
        }
    }

    private static Violation held(EntityDefinition def, Object id, NamedHold named) {
        return new Violation(null, PlatformErrorCodes.LEGAL_HOLD, def.name + " [ID: " + id
            + "] is under legal hold " + named.name(), Map.of("hold", named.name(), "holdId", named.hold().holdId()));
    }

    private Violation retention(EntityDefinition def, Map<String, ?> attributes) {
        RetentionPolicy policy = policies.covering(def.name, attributes).orElse(null);
        if (policy == null || !policy.complete()) {
            return null;
        }
        LocalDate today = policies.today(clock);
        LocalDate start = policies.dateOf(attributes == null ? null : attributes.get(policy.from()));
        if (!policy.keeps(start, today, policies.fiscalYearEnd())) {
            return null;
        }
        String until = start == null ? "?" : policy.expiry(start, policies.fiscalYearEnd()).toString();
        return new Violation(null, PlatformErrorCodes.RETENTION_ACTIVE, def.name + " must be kept until " + until,
            Map.of("until", until, "entity", def.name));
    }

    /** The holds in force on the entity type. */
    Flux<NamedHold> holds(StorageEngine engine, String entity) {
        return engine.select(HOLDS, Map.of("entity", BoundValue.of(entity), "now", BoundValue.of(clock.instant())))
            .map(row -> {
                String ids = Rows.string(row.get("entity_ids"));
                List<String> list = ids == null ? List.of() : json.readValue(ids, new TypeReference<List<String>>() {});
                return new NamedHold(new LegalHold(Rows.string(row.get("hold_id")), entity, list,
                    list.isEmpty() ? Rows.string(row.get("match_field")) : null,
                    Rows.string(row.get("match_value"))), Rows.string(row.get("hold_name")));
            });
    }
}
