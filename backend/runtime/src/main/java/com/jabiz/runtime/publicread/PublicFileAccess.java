package com.jabiz.runtime.publicread;

import com.jabiz.context.RequestContext;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.FieldDefinition;
import com.jabiz.file.FileKind;
import com.jabiz.query.BoundValue;
import com.jabiz.query.QueryCompiler;
import com.jabiz.query.RawQueryPlan;
import com.jabiz.query.SqlIdentifiers;
import com.jabiz.query.TimeSlice;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Decides whether a file may be served anonymously (docs/design/15-public-access.md section 4; decision D17): only
 * when a row visible in some public dataset refers to it through a whitelisted {@code jabiz.file} field. There is no
 * "public" flag on files; publicity follows the data.
 *
 * <p>The pairs (public dataset, whitelisted file field) follow from the metadata; one query asks whether any of them
 * refers to the file, reading each dataset exactly as public templates do (scope, versions in effect, projection).
 * Decisions are remembered for {@code jabiz.public.file-decision-ttl} in a bounded LRU map, so content taken offline
 * stays reachable at most that long plus the browser's cache; {@link #invalidate} ends it at once on this instance.
 */
@Component
public class PublicFileAccess {

    /** One field through which a public dataset exposes files. */
    public record PublicFileField(DatasetDefinition dataset, EntityDefinition entity, FieldDefinition field) {}

    private record Decision(boolean allowed, long expiresAt) {}

    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final QueryCompiler compiler;
    private final Clock clock;
    private final PublicProperties properties;
    private final LongSupplier nanoTime;
    private final Map<UUID, Decision> decisions;
    /** Counts invalidations; a lookup that an invalidation overtook is not remembered (guarded by decisions). */
    private long epoch;
    private volatile List<PublicFileField> fields;

    public PublicFileAccess(EntityDefinitionRegistry entities, DatasetRegistry datasets,
        StorageAdapterRegistry storages, QueryCompiler compiler, Clock clock, PublicProperties properties) {
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.compiler = compiler;
        this.clock = clock;
        this.properties = properties;
        // Cache expiry is throttling, not business time: the monotonic clock.
        this.nanoTime = System::nanoTime;
        int maxEntries = Math.max(1, properties.fileDecisionEntries());
        this.decisions = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<UUID, Decision> eldest) {
                return size() > maxEntries;
            }
        };
    }

    /** Every (public dataset, whitelisted file field) pair; empty when no public dataset exposes a file. */
    public List<PublicFileField> fields() {
        List<PublicFileField> known = fields;
        if (known == null) {
            List<PublicFileField> found = new ArrayList<>();
            for (DatasetDefinition dataset : datasets.all()) {
                if (!dataset.isPublic()) {
                    continue;
                }
                entities.find(dataset.targetEntityType()).ifPresent(entity -> FileKind.fieldsOf(entity).stream()
                    .filter(field -> dataset.publicRead().allows(field.name()))
                    .forEach(field -> found.add(new PublicFileField(dataset, entity, field))));
            }
            known = List.copyOf(found);
            fields = known;
        }
        return known;
    }

    /** Whether the file may be served to anonymous visitors now (possibly a remembered decision). */
    public Mono<Boolean> isPublic(UUID fileId) {
        long started;
        synchronized (decisions) {
            Decision remembered = remembered(fileId);
            if (remembered != null) {
                return Mono.just(remembered.allowed());
            }
            started = epoch;
        }
        // A lookup may read data from before a withdrawal whose invalidation runs meanwhile; its answer then serves
        // this request only and is not remembered.
        return decide(fileId).doOnNext(allowed -> remember(fileId, allowed, started));
    }

    /**
     * Forgets the decisions about these files, so the next request asks the database again. Only this instance's
     * memory is cleared; other instances forget within the decision lifetime.
     */
    public void invalidate(Collection<UUID> fileIds) {
        synchronized (decisions) {
            epoch++;
            fileIds.forEach(decisions::remove);
        }
    }

    /** The unexpired decision about the file, or null; the caller holds the lock of {@code decisions}. */
    private Decision remembered(UUID fileId) {
        Decision decision = decisions.get(fileId);
        if (decision == null) {
            return null;
        }
        if (nanoTime.getAsLong() - decision.expiresAt() >= 0) {
            decisions.remove(fileId);
            return null;
        }
        return decision;
    }

    private void remember(UUID fileId, boolean allowed, long startedEpoch) {
        Duration ttl = properties.fileDecisionTtl();
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        synchronized (decisions) {
            if (epoch == startedEpoch) {
                decisions.put(fileId, new Decision(allowed, nanoTime.getAsLong() + ttl.toNanos()));
            }
        }
    }

    private Mono<Boolean> decide(UUID fileId) {
        List<PublicFileField> exposing = fields();
        if (exposing.isEmpty()) {
            return Mono.just(false);
        }
        // Scopes of public datasets are fixed values; the anonymous context resolves them.
        RequestContext anonymous = RequestContext.anonymous(Locale.ROOT, "public-file");
        TimeSlice now = TimeSlice.asOf(clock.instant());
        Map<String, List<PublicFileField>> byPool = new LinkedHashMap<>();
        exposing.forEach(f -> byPool.computeIfAbsent(f.dataset().storage().connectionPoolRef(), p -> new ArrayList<>())
            .add(f));
        return Flux.fromIterable(byPool.entrySet())
            .concatMap(pool -> {
                QueryCompiler.Binder binder = new QueryCompiler.Binder("p_");
                String fileParam = binder.bindNamed("file_id", BoundValue.of(fileId));
                List<String> branches = new ArrayList<>();
                for (PublicFileField f : pool.getValue()) {
                    String source = compiler.templateExpression(f.dataset(), f.entity(),
                        f.dataset().scope().resolve(anonymous), now, binder);
                    String column = SqlIdentifiers.require(f.entity().physicalColumn(f.field().name()));
                    branches.add("SELECT 1 FROM " + source + " pf WHERE pf." + column + " = :" + fileParam);
                }
                String sql = "SELECT EXISTS (" + String.join(" UNION ALL ", branches) + ") AS found";
                return storages.getEngine(pool.getKey())
                    .executeRawQuery(new RawQueryPlan(sql, binder.params(), properties.maxTimeout()))
                    .next()
                    .map(row -> Boolean.TRUE.equals(row.get("found")));
            })
            .any(Boolean::booleanValue);
    }
}
