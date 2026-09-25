package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.RevertService;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.operation.OperationRecord;
import com.jabiz.runtime.temporal.TemporalPermissions;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared steps of the temporal integration tests; all writes go through the dataset API of the runtime. */
abstract class TemporalItSupport extends PostgresIntegrationTest {

    /** May correct the past, revert and read operations. */
    static final RequestContext ADMIN = new RequestContext("it-admin", null, Locale.ENGLISH, "it-admin-request",
        Set.of(), Set.of(TemporalPermissions.BACKDATE, TemporalPermissions.REVERT, TemporalPermissions.OPERATION_READ));

    static final EntityDefinition PRICE = ItTemporalFixtures.PRICE;

    private static final AtomicInteger SKU = new AtomicInteger();

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Autowired
    RevertService reverts;

    DatasetDefinition dataset(String id) {
        return datasets.findById(id).orElseThrow();
    }

    /** A SKU no other test of the class uses; the tables are shared by all tests of a class. */
    static String sku() {
        return "SKU-" + SKU.incrementAndGet() + "-" + UUID.randomUUID().toString().substring(0, 4);
    }

    Instant now() {
        return clock.instant();
    }

    void advance(Duration duration) {
        clock.advance(duration);
    }

    static Map<String, Object> attrs(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    List<EntityInstance> commit(String dataset, RequestContext as, String reason, EntityChange... changes) {
        return asRequest(as, entities.commitBatch(dataset(dataset), Arrays.asList(changes), reason)).block();
    }

    List<EntityInstance> commit(EntityChange... changes) {
        return commit(ItTemporalFixtures.PRICE_DATASET, ADMIN, null, changes);
    }

    static EntityChange insert(Map<String, Object> attributes) {
        return new EntityChange(EntityAction.INSERT, new EntityInstance(null, "ItPrice", 0, null, attributes));
    }

    static EntityChange insert(UUID id, Map<String, Object> attributes, Instant effective) {
        Map<String, Object> withId = new LinkedHashMap<>(attributes);
        withId.put("priceId", id);
        return new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItPrice", 0, null, withId), effective);
    }

    static EntityChange update(Object id, long version, Map<String, Object> attributes, Instant effective) {
        return new EntityChange(EntityAction.UPDATE, new EntityInstance(id, "ItPrice", version, null, attributes),
            effective);
    }

    static EntityChange delete(Object id, long version, Instant effective) {
        return new EntityChange(EntityAction.DELETE, new EntityInstance(id, "ItPrice", version, null, Map.of()),
            effective);
    }

    static EntityChange cancel(Object id, long version, Instant effective) {
        return new EntityChange(EntityAction.CANCEL_SCHEDULED,
            new EntityInstance(id, "ItPrice", version, null, Map.of()), effective);
    }

    /** Inserts a price in effect from now: region JP, DRAFT. */
    EntityInstance newPrice(String sku, long amount) {
        UUID id = UUID.randomUUID();
        return commit(insert(id, attrs("sku", sku, "region", "JP", "amount", amount, "status", "DRAFT"), null))
            .getFirst();
    }

    EntityInstance read(Object id) {
        return read(ItTemporalFixtures.PRICE_DATASET, id, null, null);
    }

    EntityInstance read(Object id, Instant asOf) {
        return read(ItTemporalFixtures.PRICE_DATASET, id, asOf, null);
    }

    EntityInstance read(String dataset, Object id, Instant asOf, Instant knownAt) {
        return asRequest(ADMIN, entities.findById(dataset(dataset), PRICE, id, asOf, knownAt)).block();
    }

    List<EntityInstance> queryAll(String dataset) {
        return asRequest(ADMIN, entities.query(dataset(dataset), PRICE, EntityQuery.builder().limit(100).build())
            .collectList()).block();
    }

    /** Number of prices with the SKU the dataset shows (other tests' rows share the table). */
    long count(String dataset, String sku) {
        return asRequest(ADMIN, entities.count(dataset(dataset), PRICE, EntityQuery.builder()
            .where(new QueryPredicate.Eq("sku", sku)).build())).block();
    }

    OperationRecord revert(long processSeqId, String reason) {
        return asRequest(ADMIN, reverts.revert(processSeqId, reason)).block();
    }

    /** Operation that wrote the latest version of the instance. */
    long lastOperation(Object id) {
        return ((Number) query("SELECT process_seq_id FROM it_price WHERE price_id = ? ORDER BY version_no DESC LIMIT 1",
            UUID.fromString(id.toString())).getFirst().get("process_seq_id")).longValue();
    }

    List<Map<String, Object>> versions(Object id) {
        return query("SELECT * FROM it_price WHERE price_id = ? ORDER BY version_no", UUID.fromString(id.toString()));
    }

    static <T> T block(Mono<T> mono) {
        return mono.block();
    }
}
