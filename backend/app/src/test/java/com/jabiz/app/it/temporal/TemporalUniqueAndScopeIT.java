package com.jabiz.app.it.temporal;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Uniqueness of temporal entities (decision D6) and scopes applied after the current version (decision D3). */
class TemporalUniqueAndScopeIT extends TemporalItSupport {

    @Autowired
    AdvancedQueryExecutor templates;

    private static void assertUniqueViolation(Throwable error) {
        assertThat(error).isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
            .singleElement().satisfies(v -> {
                assertThat(v.ruleCode()).isEqualTo("UNIQUE_VIOLATION");
                assertThat(v.field()).isEqualTo("sku");
            }));
    }

    /** Acceptance: of concurrent inserts of the same value exactly one succeeds. */
    @Test
    void ofConcurrentInsertsOfTheSameValueOneSucceeds() {
        String sku = sku();
        List<Object> outcomes = Flux.range(0, 4)
            .flatMap(i -> asRequest(ADMIN, entities.commitBatch(dataset(ItTemporalFixtures.PRICE_DATASET),
                    List.of(insert(UUID.randomUUID(), attrs("sku", sku, "region", "JP"), null)), null))
                .map(result -> (Object) result.getFirst())
                .onErrorResume(error -> Mono.just(error))
                .subscribeOn(Schedulers.parallel()))
            .collectList()
            .block();

        assertThat(outcomes).filteredOn(EntityInstance.class::isInstance).hasSize(1);
        assertThat(outcomes).filteredOn(ValidationException.class::isInstance).hasSize(3)
            .allSatisfy(error -> assertUniqueViolation((Throwable) error));
        assertThat(query("SELECT count(*) AS n FROM it_price WHERE sku = ?", sku).getFirst().get("n")).isEqualTo(1L);
    }

    /** Acceptance: a value a scheduled version will take is taken already. */
    @Test
    void valuesOfScheduledVersionsAreTaken() {
        EntityInstance a = newPrice(sku(), 100);
        String future = sku();
        commit(update(a.id(), 1, attrs("sku", future), now().plus(Duration.ofDays(3))));

        assertThatThrownBy(() -> newPrice(future, 100)).satisfies(TemporalUniqueAndScopeIT::assertUniqueViolation);

        EntityInstance b = newPrice(sku(), 100);
        assertThatThrownBy(() -> commit(update(b.id(), 1, attrs("sku", future), null)))
            .satisfies(TemporalUniqueAndScopeIT::assertUniqueViolation);
    }

    @Test
    void theOwnValueAndValuesOfDeletedOrReplacedVersionsAreFree() {
        String sku = sku();
        EntityInstance a = newPrice(sku, 100);
        // Changing other fields keeps the entity's own value.
        assertThat(commit(update(a.id(), 1, attrs("amount", 120), null))).hasSize(1);

        advance(Duration.ofMinutes(1));
        commit(update(a.id(), 2, attrs("sku", sku()), null));
        // The old value is only held by a replaced version now.
        EntityInstance b = newPrice(sku, 100);

        advance(Duration.ofMinutes(1));
        commit(delete(b.id(), 1, null));
        assertThat(newPrice(sku, 100)).isNotNull();
    }

    /** Acceptance: once moved out of the scope an entity is invisible, never shown in its older version. */
    @Test
    void anEntityMovedOutOfTheScopeIsNotSeenInAnyVersion() {
        EntityInstance price = newPrice(sku(), 100);
        String jp = ItTemporalFixtures.PRICE_JP_DATASET;
        assertThat(ids(queryAll(jp))).contains(price.id());
        String sku = price.get("sku");
        assertThat(count(jp, sku)).isEqualTo(1);
        assertThat(skus(jp)).contains((String) price.get("sku"));
        assertThat(read(jp, price.id(), null, null)).isNotNull();

        advance(Duration.ofMinutes(1));
        commit(update(price.id(), 1, attrs("region", "US"), null));

        // The version still in the scope (region JP, version 1) must not come back through any path.
        assertThat(ids(queryAll(jp))).doesNotContain(price.id());
        assertThat(count(jp, sku)).isZero();
        assertThat(count(ItTemporalFixtures.PRICE_DATASET, sku)).isEqualTo(1);
        assertThat(skus(jp)).doesNotContain((String) price.get("sku"));
        assertThat(read(jp, price.id(), null, null)).isNull();
        // At a time the entity was still in the scope it can be read there.
        assertThat(read(jp, price.id(), price.<Instant>get("effectStartTime"), null)).isNotNull();
        // And it cannot be changed through the scoped dataset any more.
        assertThatThrownBy(() -> commit(jp, ADMIN, null, update(price.id(), 2, attrs("note", "x"), null)))
            .isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    void writesThroughAScopedDatasetStayWithinIt() {
        assertThatThrownBy(() -> commit(ItTemporalFixtures.PRICE_JP_DATASET, ADMIN, null,
            insert(UUID.randomUUID(), attrs("sku", sku(), "region", "US"), null)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("OUT_OF_SCOPE"));
        EntityInstance inside = commit(ItTemporalFixtures.PRICE_JP_DATASET, ADMIN, null,
            insert(UUID.randomUUID(), attrs("sku", sku(), "region", "JP"), null)).getFirst();
        assertThatThrownBy(() -> commit(ItTemporalFixtures.PRICE_JP_DATASET, ADMIN, null,
            update(inside.id(), 1, attrs("region", "US"), null)))
            .isInstanceOfSatisfying(BusinessRuleViolationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("OUT_OF_SCOPE"));
    }

    @Test
    void timeTravelCanBeForbidden() {
        EntityInstance price = newPrice(sku(), 100);
        String current = ItTemporalFixtures.PRICE_CURRENT_DATASET;
        assertThat(read(current, price.id(), null, null)).isNotNull();
        assertThatThrownBy(() -> read(current, price.id(), now(), null))
            .isInstanceOfSatisfying(ValidationException.class, e -> assertThat(e.violations())
                .extracting(v -> v.ruleCode()).containsExactly("TIME_TRAVEL_NOT_ALLOWED"));
        assertThatThrownBy(() -> asRequest(ADMIN, entities.history(dataset(current), PRICE, price.id())).block())
            .isInstanceOf(ValidationException.class);
    }

    private List<String> skus(String dataset) {
        return asRequest(ADMIN, templates.execute(dataset(dataset), ItTemporalFixtures.SKUS, Map.of())
            .map(row -> (String) row.getRaw("sku")).collectList()).block();
    }

    private static List<Object> ids(List<EntityInstance> instances) {
        return instances.stream().map(EntityInstance::id).toList();
    }

}
