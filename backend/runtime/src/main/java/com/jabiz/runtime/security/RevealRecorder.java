package com.jabiz.runtime.security;

import com.jabiz.context.RequestContext;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Keeps {@code sys_reveal_record}, the append-only record of every plain-text display of masked fields
 * (docs/design/10-security.md section 13.1, decision D28 item 7), and reads it back for auditors. A display is
 * recorded before the value leaves the platform: when the record cannot be written, nothing is shown.
 */
@Component
public class RevealRecorder {

    /** How the plain values left. */
    public enum Kind {
        /** One value of one instance, asked for by a holder of the permission. */
        VALUE,
        /** The plain columns of a SQL template run or a report export. */
        QUERY,
        /** The plain columns of a data export. */
        EXPORT
    }

    /** One record as the API shows it. */
    public record RevealEntry(UUID revealId, Instant revealedAt, String actorId, String kind, String resource,
        String entity, String entityId, List<String> fields, Long rowCount) {}

    public record RevealPage(List<RevealEntry> items, long total, int offset, int limit) {}

    /** Filters of the records, combined with AND; all optional. */
    public record RevealQuery(String actorId, String entity, String entityId, Instant from, Instant to, int offset,
        int limit) {}

    static final String TABLE = "sys_reveal_record";

    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final EntityIdGenerator ids;
    private final Clock clock;

    public RevealRecorder(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, EntityIdGenerator ids, Clock clock) {
        this.storages = storages;
        this.poolRef = poolRef;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Records that {@code fields} of {@code entity} left in plain text to the actor of {@code context}.
     *
     * @param resource the dataset, template or export the values came through
     * @param entityId the instance, for {@link Kind#VALUE}; null otherwise
     * @param rowCount the number of rows of a run or export; null for {@link Kind#VALUE}
     */
    public Mono<Void> record(RequestContext context, Kind kind, String resource, String entity, String entityId,
        Collection<String> fields, Long rowCount) {
        return Mono.defer(() -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reveal_id", UUID.fromString(String.valueOf(ids.next(null))));
            row.put("revealed_at", clock.instant());
            row.put("actor_id", context.actorId());
            row.put("request_id", context.requestId());
            row.put("kind", kind.name());
            row.put("resource", resource);
            row.put("entity", entity);
            row.put("entity_id", entityId);
            row.put("fields", String.join(",", new TreeSet<>(fields)));
            row.put("row_count", rowCount);
            return storages.getEngine(poolRef).insert(TABLE, row);
        });
    }

    /** One page of records, newest first. */
    public Mono<RevealPage> find(RevealQuery query) {
        StorageEngine engine = storages.getEngine(poolRef);
        List<String> conditions = new ArrayList<>(List.of("TRUE"));
        Map<String, BoundValue> params = new LinkedHashMap<>();
        if (query.actorId() != null) {
            conditions.add("actor_id = :actor");
            params.put("actor", BoundValue.of(query.actorId()));
        }
        if (query.entity() != null) {
            conditions.add("entity = :entity");
            params.put("entity", BoundValue.of(query.entity()));
        }
        if (query.entityId() != null) {
            conditions.add("entity_id = :entityId");
            params.put("entityId", BoundValue.of(query.entityId()));
        }
        if (query.from() != null) {
            conditions.add("revealed_at >= :from");
            params.put("from", BoundValue.of(query.from()));
        }
        if (query.to() != null) {
            conditions.add("revealed_at < :to");
            params.put("to", BoundValue.of(query.to()));
        }
        String where = " FROM " + TABLE + " WHERE " + String.join(" AND ", conditions);
        Map<String, BoundValue> pageParams = new LinkedHashMap<>(params);
        pageParams.put("offset", BoundValue.of((long) query.offset()));
        pageParams.put("limit", BoundValue.of((long) query.limit()));
        Mono<Long> total = engine.select("SELECT count(*) AS total" + where, params)
            .next().map(row -> Rows.longValue(row.get("total")));
        Mono<List<RevealEntry>> page = engine.select("SELECT *" + where
                + " ORDER BY revealed_at DESC, reveal_id DESC OFFSET :offset LIMIT :limit", pageParams)
            .map(RevealRecorder::entry)
            .collectList();
        return Mono.zip(page, total).map(parts -> new RevealPage(parts.getT1(), parts.getT2(), query.offset(),
            query.limit()));
    }

    private static RevealEntry entry(Map<String, Object> row) {
        Object count = row.get("row_count");
        return new RevealEntry((UUID) row.get("reveal_id"), Rows.instant(row.get("revealed_at")),
            Rows.string(row.get("actor_id")), Rows.string(row.get("kind")), Rows.string(row.get("resource")),
            Rows.string(row.get("entity")), Rows.string(row.get("entity_id")),
            List.of(Rows.string(row.get("fields")).split(",")), count == null ? null : Rows.longValue(count));
    }
}
