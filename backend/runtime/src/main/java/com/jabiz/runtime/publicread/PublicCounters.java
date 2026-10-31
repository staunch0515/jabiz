package com.jabiz.runtime.publicread;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.dataset.PublicCounter;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.query.TimeSlice;
import com.jabiz.entity.ValidationException;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.observability.PlatformObservations;
import com.jabiz.runtime.process.entity.EntityIdGenerator;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import io.micrometer.common.KeyValues;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts what anonymous visitors do on public pages (docs/design/15-public-access.md section 10; decision D65).
 *
 * <p>A count needs a key visible in the counter's public dataset now (read as public templates read it; the decision
 * is remembered for {@code jabiz.public.file-decision-ttl}) and a visitor not yet counted for that key that day. The
 * visitor is a digest of the client address and the browser's {@code User-Agent}, kept in memory only, in a bounded LRU
 * map; it never reaches the database or the logs. Counts are added up in memory and written every
 * {@code jabiz.public.counters.flush-interval} and at shutdown, each key's count of a day added to its row; what a
 * write fails to store is kept for the next one. All of it is approximate: each instance remembers its own visitors,
 * and counts held when an instance dies are lost.
 */
@Component
public class PublicCounters {

    /** What became of one count; only for observations (the visitor is told nothing). */
    public enum Result { COUNTED, REPEATED, IGNORED }

    private static final Logger log = LoggerFactory.getLogger(PublicCounters.class);

    private static final String ADD = """
        INSERT INTO sys_public_counter_day (row_id, counter_name, counter_key, day, hits, owner_key)
        VALUES (CAST(:id AS uuid), :counter, :key, :day, :hits, :owner)
        ON CONFLICT (counter_name, counter_key, day)
        DO UPDATE SET hits = sys_public_counter_day.hits + EXCLUDED.hits,
            owner_key = COALESCE(sys_public_counter_day.owner_key, EXCLUDED.owner_key)
        RETURNING hits""";

    /** One key's count of one day, waiting to be written, with the key's owner when the counter has one. */
    record Pending(String counter, String key, LocalDate day, String owner) {}

    /** Whether the key is visible in the counter's public dataset, and its row's owner (null without). */
    private record Visibility(boolean allowed, String owner) {
        static final Visibility NONE = new Visibility(false, null);
    }

    private record Decision(Visibility visibility, long expiresAt) {}

    private final Map<String, PublicCounter> counters;
    private final DatasetRegistry datasets;
    private final EntityDefinitionRegistry entities;
    private final StorageAdapterRegistry storages;
    private final QueryCompiler compiler;
    private final EntityIdGenerator ids;
    private final Clock clock;
    private final PublicProperties properties;
    private final PlatformObservations observations;
    private final String poolRef;
    private final LongSupplier nanoTime;

    private final Map<String, Decision> decisions;
    private final Map<String, Boolean> visitors;
    /** Counts not yet written (guarded by itself). */
    private final Map<Pending, Long> pending = new LinkedHashMap<>();
    private final Map<String, Optional<Target>> targets = new ConcurrentHashMap<>();
    private volatile Disposable flushing;

    public PublicCounters(ObjectProvider<PublicCounter> declared, DatasetRegistry datasets,
        EntityDefinitionRegistry entities, StorageAdapterRegistry storages, QueryCompiler compiler,
        EntityIdGenerator ids, Clock clock, PublicProperties properties, PlatformObservations observations,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        Map<String, PublicCounter> byName = new LinkedHashMap<>();
        // A name declared twice is reported by PublicCounterCheck; the first declaration counts meanwhile.
        declared.orderedStream().forEach(counter -> byName.putIfAbsent(counter.name(), counter));
        this.counters = Map.copyOf(byName);
        this.datasets = datasets;
        this.entities = entities;
        this.storages = storages;
        this.compiler = compiler;
        this.ids = ids;
        this.clock = clock;
        this.properties = properties;
        this.observations = observations;
        this.poolRef = poolRef;
        // Cache expiry is throttling, not business time: the monotonic clock.
        this.nanoTime = System::nanoTime;
        this.decisions = lru(Math.max(1, properties.fileDecisionEntries()));
        this.visitors = lru(Math.max(1, properties.counters().maxVisitors()));
    }

    private static <V> Map<String, V> lru(int maxEntries) {
        return new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** The declared counter of that name. */
    public Optional<PublicCounter> find(String name) {
        return Optional.ofNullable(counters.get(name));
    }

    /**
     * Counts one visit of {@code visitor} (the client address and browser, any text identifying them alike) to
     * {@code rawKey} of the counter, unless the key is not visible in the counter's public dataset now or the visitor
     * was counted for it today.
     */
    public Mono<Result> count(PublicCounter counter, String rawKey, String visitor) {
        return Mono.defer(() -> {
            Target target = target(counter);
            String key = target == null ? null : PublicCounter.normalizeKey(rawKey, target.ids());
            if (key == null) {
                return Mono.just(Result.IGNORED);
            }
            LocalDate day = LocalDate.ofInstant(clock.instant(), counter.zone());
            String seen = digest(counter.name() + '\n' + key + '\n' + day + '\n' + visitor);
            synchronized (visitors) {
                if (visitors.containsKey(seen)) {
                    return Mono.just(Result.REPEATED);
                }
            }
            return visible(counter, target, key).map(visibility -> visibility.allowed()
                ? add(seen, new Pending(counter.name(), key, day, visibility.owner())) : Result.IGNORED);
        }).doOnNext(result -> observations.event(PlatformObservations.PUBLIC_COUNTER,
            KeyValues.of("counter", counter.name(), "result", result.name().toLowerCase(Locale.ROOT))));
    }

    private Result add(String seen, Pending count) {
        // Lock order: visitors, then pending. The visitor is remembered only when counted, so a count refused for
        // want of room does not keep them from counting later that day.
        synchronized (visitors) {
            if (visitors.containsKey(seen)) {
                return Result.REPEATED;
            }
            synchronized (pending) {
                Long held = pending.get(count);
                if (held == null && pending.size() >= properties.counters().maxPending()) {
                    // Bounded memory between two writes: a burst of new keys is not counted rather than exhaust it.
                    return Result.IGNORED;
                }
                pending.put(count, held == null ? 1 : held + 1);
            }
            visitors.put(seen, Boolean.TRUE);
        }
        return Result.COUNTED;
    }

    /** The counts held in memory, by key and day (tests). */
    Map<Pending, Long> pending() {
        synchronized (pending) {
            return Map.copyOf(pending);
        }
    }

    /**
     * Writes the counts held in memory, each added to its row; those a statement fails to store are held again for
     * the next write. Completes when all were tried.
     */
    public Mono<Void> flush() {
        return Mono.defer(() -> {
            Map<Pending, Long> taken;
            synchronized (pending) {
                if (pending.isEmpty()) {
                    // Nothing to write: no observation every tick of an idle timer.
                    return Mono.empty();
                }
                taken = new LinkedHashMap<>(pending);
                pending.clear();
            }
            return observations.mono(PlatformObservations.PUBLIC_COUNTER_FLUSH, "public counters flush",
                KeyValues.empty(), write(taken));
        });
    }

    private Mono<Void> write(Map<Pending, Long> taken) {
        return Mono.defer(() -> {
            var engine = storages.getEngine(poolRef);
            return Flux.fromIterable(taken.entrySet())
                .concatMap(entry -> {
                    Pending count = entry.getKey();
                    Map<String, BoundValue> params = new LinkedHashMap<>();
                    params.put("id", BoundValue.of(ids.next(PublicCounterEntities.DAY_ENTITY)));
                    params.put("counter", BoundValue.of(count.counter()));
                    params.put("key", BoundValue.of(count.key()));
                    params.put("day", BoundValue.of(count.day()));
                    params.put("hits", BoundValue.of(entry.getValue()));
                    params.put("owner", count.owner() == null ? BoundValue.nullOf(String.class)
                        : BoundValue.of(count.owner()));
                    return engine.select(ADD, params).then()
                        .onErrorResume(e -> {
                            log.warn("Public counter {} not written, kept for the next write: {}", count.counter(),
                                NestedExceptionUtils.getMostSpecificCause(e).toString());
                            hold(count, entry.getValue());
                            return Mono.empty();
                        });
                })
                .then();
        });
    }

    private void hold(Pending count, long hits) {
        synchronized (pending) {
            pending.merge(count, hits, Long::sum);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void start() {
        Duration interval = properties.counters().flushInterval();
        if (counters.isEmpty() || interval.isZero() || interval.isNegative() || flushing != null) {
            return;
        }
        flushing = Flux.interval(interval, interval, Schedulers.parallel())
            .onBackpressureDrop()
            .concatMap(tick -> flush().onErrorResume(e -> {
                log.warn("Public counters not written: {}", e.toString());
                return Mono.empty();
            }), 1)
            .subscribe();
    }

    @jakarta.annotation.PreDestroy
    public synchronized void stop() {
        if (flushing != null) {
            flushing.dispose();
            flushing = null;
        }
        try {
            flush().block(Duration.ofSeconds(10));
        } catch (RuntimeException e) {
            log.warn("Public counters not written at shutdown: {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ keys

    /**
     * The counter's dataset and entity, its key's column and whether it holds ids, and its owner's column (null
     * without); null when not declared.
     */
    private record Target(DatasetDefinition dataset, EntityDefinition entity, String column, boolean ids,
        String ownerColumn) {}

    private Target target(PublicCounter counter) {
        if (counters.get(counter.name()) != counter) {
            // Not the declared one (tests): resolved each time, never remembered under a declared name.
            return resolve(counter);
        }
        // Declarations do not change while the application runs: resolved once per counter.
        return targets.computeIfAbsent(counter.name(), name -> Optional.ofNullable(resolve(counter))).orElse(null);
    }

    private Target resolve(PublicCounter counter) {
        DatasetDefinition dataset = datasets.findById(counter.dataset()).filter(DatasetDefinition::isPublic)
            .orElse(null);
        EntityDefinition entity = dataset == null ? null : entities.find(dataset.targetEntityType()).orElse(null);
        if (entity == null || !counter.problems(dataset, entity).isEmpty()) {
            // Default deny: besides the startup check, the declaration must hold right here.
            return null;
        }
        return new Target(dataset, entity, SqlIdentifiers.require(entity.physicalColumn(counter.field())),
            PublicCounter.ids(entity.fields.get(counter.field())),
            counter.owner() == null ? null : SqlIdentifiers.require(entity.physicalColumn(counter.owner())));
    }

    private Mono<Visibility> visible(PublicCounter counter, Target target, String key) {
        String remembered = counter.name() + '\n' + key;
        synchronized (decisions) {
            Decision decision = decisions.get(remembered);
            if (decision != null && nanoTime.getAsLong() - decision.expiresAt() < 0) {
                return Mono.just(decision.visibility());
            }
            decisions.remove(remembered);
        }
        // Scopes of public datasets are fixed values; the anonymous context resolves them.
        RequestContext anonymous = RequestContext.anonymous(Locale.ROOT, "public-counter");
        QueryCompiler.Binder binder = new QueryCompiler.Binder("p_");
        Object value;
        try {
            // As the column holds it: an id may be a uuid column or text (an ordinary table's semantic identity).
            value = compiler.fieldValue(target.entity(), target.entity().fields.get(counter.field()), key);
        } catch (ValidationException e) {
            return Mono.just(Visibility.NONE);
        }
        String keyParam = binder.bindNamed("counter_key", BoundValue.of(value));
        String source = compiler.templateExpression(target.dataset(), target.entity(),
            target.dataset().scope().resolve(anonymous), TimeSlice.asOf(clock.instant()), binder);
        // One visible row is enough; with an owner, it also says whose the key is (the owner is the row's, so any
        // visible row of the key gives the same).
        String owner = target.ownerColumn() == null ? "NULL" : "pc." + target.ownerColumn();
        String sql = "SELECT " + owner + " AS owner_key FROM " + source + " pc WHERE pc." + target.column() + " = :"
            + keyParam + " LIMIT 1";
        return storages.getEngine(target.dataset().storage().connectionPoolRef())
            .executeRawQuery(new RawQueryPlan(sql, binder.params(), properties.maxTimeout()))
            .next()
            .map(row -> new Visibility(true, owner(row.get("owner_key"))))
            .defaultIfEmpty(Visibility.NONE)
            .doOnNext(visibility -> remember(remembered, visibility));
    }

    /** The owner as stored: its text, or null when there is none or it does not fit the column. */
    private static String owner(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() || text.length() > PublicCounter.MAX_OWNER_LENGTH ? null : text;
    }

    private void remember(String remembered, Visibility visibility) {
        Duration ttl = properties.fileDecisionTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        synchronized (decisions) {
            decisions.put(remembered, new Decision(visibility, nanoTime.getAsLong() + ttl.toNanos()));
        }
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            // Half the digest is plenty to tell visitors apart within a day, and halves the memory they take.
            return Base64.getEncoder().withoutPadding().encodeToString(Arrays.copyOf(hash, 16));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
