package com.jabiz.runtime.publicread;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What {@link PublicCounters} holds in memory (docs/design/15-public-access.md section 10; decision D65): the
 * once-a-day rule, the bound on keys between two writes and the counts a failed write keeps. The database itself is
 * {@code PublicCounterIT}'s.
 */
class PublicCountersTest {

    private static final EntityDefinition PRODUCT = EntityDefinition.define("Product", eb -> {
        eb.physicalTable("t_product");
        eb.primaryKey("productId");
        eb.field("productId", f -> f.physicalColumn("product_id").asSemanticIdentity("urn:test:product"));
        eb.field("sku", f -> f.physicalColumn("sku").asText(40));
        eb.field("active", f -> f.physicalColumn("active").asBool());
    });

    private static final DatasetDefinition PUBLIC = DatasetDefinition.define("urn:test:public:Product", d -> d
        .targetEntityType("Product")
        .scope(s -> s.fixed("active", true))
        .publicRead(p -> p.fields("productId", "sku"))
        .storage(s -> s.connectionPoolRef("default")));

    private static final PublicCounter VIEWS = PublicCounter.define("shop.view", c -> c.keys(PUBLIC.resourceId(),
        "productId").owner("sku"));

    private final StorageEngine engine = mock(StorageEngine.class);
    private final List<Map<String, BoundValue>> written = new ArrayList<>();
    private final AtomicInteger failures = new AtomicInteger();

    private PublicCounters counters(int maxPending) {
        @SuppressWarnings("unchecked")
        ObjectProvider<PublicCounter> declared = mock(ObjectProvider.class);
        when(declared.orderedStream()).thenAnswer(i -> Stream.of(VIEWS));
        DatasetRegistry datasets = mock(DatasetRegistry.class);
        when(datasets.findById(PUBLIC.resourceId())).thenReturn(Optional.of(PUBLIC));
        EntityDefinitionRegistry entities = mock(EntityDefinitionRegistry.class);
        when(entities.find("Product")).thenReturn(Optional.of(PRODUCT));
        StorageAdapterRegistry storages = mock(StorageAdapterRegistry.class);
        when(storages.getEngine(anyString())).thenReturn(engine);
        // A visible row of the key, with its owner (the key's row says whose it is).
        when(engine.executeRawQuery(any())).thenAnswer(i -> Flux.just(Map.of("owner_key", "seller-1")));
        when(engine.select(anyString(), anyMap())).thenAnswer(i -> {
            if (failures.getAndDecrement() > 0) {
                return Flux.error(new IllegalStateException("database down"));
            }
            written.add(i.getArgument(1));
            return Flux.just(Map.of("hits", 1L));
        });
        PublicProperties properties = new PublicProperties(true, null, null, null, null, null, null, null,
            new PublicProperties.Counters(Duration.ZERO, 100, maxPending));
        return new PublicCounters(declared, datasets, entities, storages, new QueryCompiler(),
            definition -> UUID.randomUUID().toString(), Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"),
            ZoneOffset.UTC), properties, PlatformObservations.NOOP, "default");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void countsAVisitorOnceAKeyAndDay() {
        PublicCounters counters = counters(10);
        String key = key();
        assertThat(counters.count(VIEWS, key, "a").block()).isEqualTo(PublicCounters.Result.COUNTED);
        assertThat(counters.count(VIEWS, key, "a").block()).isEqualTo(PublicCounters.Result.REPEATED);
        assertThat(counters.count(VIEWS, key, "b").block()).isEqualTo(PublicCounters.Result.COUNTED);
        assertThat(counters.count(VIEWS, "not-a-uuid", "a").block()).isEqualTo(PublicCounters.Result.IGNORED);
        assertThat(counters.pending()).containsExactly(Map.entry(
            new PublicCounters.Pending("shop.view", key, LocalDate.parse("2026-03-01"), "seller-1"), 2L));
        // The key's visibility is asked once and remembered; a repeat and a key not an id never ask.
        verify(engine, org.mockito.Mockito.times(1)).executeRawQuery(any());
    }

    @Test
    void holdsAtMostSoManyKeysBetweenTwoWrites() {
        PublicCounters counters = counters(2);
        String first = key();
        assertThat(counters.count(VIEWS, first, "a").block()).isEqualTo(PublicCounters.Result.COUNTED);
        assertThat(counters.count(VIEWS, key(), "a").block()).isEqualTo(PublicCounters.Result.COUNTED);
        String third = key();
        assertThat(counters.count(VIEWS, third, "a").block()).isEqualTo(PublicCounters.Result.IGNORED);
        // A key held already still counts.
        assertThat(counters.count(VIEWS, first, "b").block()).isEqualTo(PublicCounters.Result.COUNTED);
        counters.flush().block();
        assertThat(written).hasSize(2);
        // The visitor refused for want of room was not remembered: they count once there is room.
        assertThat(counters.count(VIEWS, third, "a").block()).isEqualTo(PublicCounters.Result.COUNTED);
    }

    @Test
    void keepsWhatAWriteFailsToStoreForTheNext() {
        PublicCounters counters = counters(10);
        String key = key();
        counters.count(VIEWS, key, "a").block();
        failures.set(1);
        counters.flush().block();
        assertThat(written).isEmpty();
        counters.count(VIEWS, key, "b").block();
        assertThat(counters.pending().values()).containsExactly(2L);

        counters.flush().block();
        assertThat(written).singleElement().satisfies(params -> {
            assertThat(params.get("key").value()).isEqualTo(key);
            assertThat(params.get("hits").value()).isEqualTo(2L);
            assertThat(params.get("day").value()).isEqualTo(LocalDate.parse("2026-03-01"));
            assertThat(params.get("owner").value()).isEqualTo("seller-1");
        });
        assertThat(counters.pending()).isEmpty();
        // Nothing held: nothing written.
        counters.flush().block();
        assertThat(written).hasSize(1);
    }

    @Test
    void bindsTheKeyAsItsColumnHoldsIt() {
        // An ordinary table's semantic identity is text (not a uuid column): the key is looked up as text.
        PublicCounters counters = counters(10);
        String key = key();
        counters.count(VIEWS, key, "a").block();
        org.mockito.ArgumentCaptor<com.jabiz.query.RawQueryPlan> plan =
            org.mockito.ArgumentCaptor.forClass(com.jabiz.query.RawQueryPlan.class);
        verify(engine).executeRawQuery(plan.capture());
        assertThat(plan.getValue().bindParams().values()).anySatisfy(bound -> {
            assertThat(bound.value()).isEqualTo(key);
            assertThat(bound.type()).isEqualTo(String.class);
        });
        assertThat(plan.getValue().sql()).contains("pc.sku AS owner_key").contains("LIMIT 1");
    }

    @Test
    void aKeyWithoutAVisibleRowIsNotCounted() {
        PublicCounters counters = counters(10);
        when(engine.executeRawQuery(any())).thenAnswer(i -> Flux.empty());
        assertThat(counters.count(VIEWS, key(), "a").block()).isEqualTo(PublicCounters.Result.IGNORED);
        assertThat(counters.pending()).isEmpty();
    }

    @Test
    void anUndeclaredOrBrokenCounterCountsNothing() {
        PublicCounters counters = counters(10);
        PublicCounter broken = PublicCounter.define("shop.broken", c -> c.keys(PUBLIC.resourceId(), "active"));
        assertThat(counters.count(broken, key(), "a").block()).isEqualTo(PublicCounters.Result.IGNORED);
        assertThat(counters.find("shop.view")).contains(VIEWS);
        assertThat(counters.find("shop.broken")).isEmpty();
        verify(engine, never()).executeRawQuery(any());
    }
}
