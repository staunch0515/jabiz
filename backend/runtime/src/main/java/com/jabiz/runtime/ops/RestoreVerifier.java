package com.jabiz.runtime.ops;

import com.jabiz.context.RequestContext;
import com.jabiz.query.custom.AdvancedQueryDefinition;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.file.FileStore;
import com.jabiz.runtime.integrity.IntegrityProcesses;
import com.jabiz.runtime.process.ExecutionOptions;
import com.jabiz.runtime.process.ProcessExecutor;
import com.jabiz.runtime.query.AdvancedQueryExecutor;
import com.jabiz.runtime.query.SqlTemplateRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import com.jabiz.security.MfaSecretCipher;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.entity.EntityDefinitionRegistry;
import com.jabiz.runtime.security.rotation.KeyRotationProcesses;
import com.jabiz.runtime.security.secret.SecretSealer;
import com.jabiz.security.SealedField;
import com.jabiz.security.SealedFields;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Checks a restored copy of the database and files (docs/design/13-observability-ops.md section 8): the seal chain
 * ({@code INTEGRITY_VERIFY}, so {@code JABIZ_INTEGRITY_KEY} is the right one), every current TOTP secret opens
 * ({@code JABIZ_MFA_KEY}), every declared sealed value opens ({@code JABIZ_SEAL_KEY}), every registered file is in the file store at its size, the time of the last committed
 * operation (against the target time of the restore, the recovery point), and the application's own checks: SQL
 * templates named in {@code jabiz.ops.verify-restore.templates} that return no rows when all is well. Nothing is
 * changed but the record {@code INTEGRITY_VERIFY} keeps of itself. Values are never reported, only counts.
 */
@Component
public class RestoreVerifier {

    static final String ENABLED = "jabiz.ops.verify-restore.enabled";
    static final String TEMPLATES = "jabiz.ops.verify-restore.templates";
    static final String TARGET_TIME = "jabiz.ops.verify-restore.target-time";
    static final String REKEY = "jabiz.ops.verify-restore.rekey";
    /** Rows of a sealed field opened at most (a larger table is reported as checked in part). */
    static final int SEALED_ROWS = 100_000;
    /** The request id of a verification's own operations, which are not part of the restored data. */
    static final String REQUEST_PREFIX = "verify-restore-";
    /** Rows of an application check shown as found; more are counted as "more than". */
    static final int TEMPLATE_ROWS = 100;
    private static final int FILE_CONCURRENCY = 8;
    private static final int MAX_REKEY_RUNS = 10_000;

    /** One check: its name, whether it passed and what it found (counts, never values). */
    public record Item(String name, boolean passed, String detail) {}

    /** All checks; passed when every one passed. */
    public record Report(Instant checkedAt, List<Item> items) {
        public Report {
            items = List.copyOf(items);
        }

        public boolean passed() {
            return items.stream().allMatch(Item::passed);
        }
    }

    private final ProcessExecutor processes;
    private final MfaSecretCipher mfa;
    private final FileStore files;
    private final SqlTemplateRegistry templates;
    private final AdvancedQueryExecutor queries;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final List<String> templateIds;
    private final Instant targetTime;
    private final boolean rekey;
    private final SecretSealer sealer;
    private final ObjectProvider<SealedFields> sealedFields;
    private final EntityDefinitionRegistry entities;
    private final DatasetRegistry datasets;
    private final DatasetEntityManager entityManager;
    private final Clock clock;

    public RestoreVerifier(ProcessExecutor processes, MfaSecretCipher mfa, FileStore files,
        SqlTemplateRegistry templates, AdvancedQueryExecutor queries, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${" + TEMPLATES + ":}") String templateIds, @Value("${" + TARGET_TIME + ":}") String targetTime,
        @Value("${" + REKEY + ":false}") boolean rekey, SecretSealer sealer,
        ObjectProvider<SealedFields> sealedFields, EntityDefinitionRegistry entities, DatasetRegistry datasets,
        DatasetEntityManager entityManager, Clock clock) {
        this.processes = processes;
        this.mfa = mfa;
        this.files = files;
        this.templates = templates;
        this.queries = queries;
        this.storages = storages;
        this.poolRef = poolRef;
        this.templateIds = Arrays.stream(templateIds.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
        this.targetTime = targetTime == null || targetTime.isBlank() ? null : OffsetDateTime.parse(targetTime.strip())
            .toInstant();
        this.rekey = rekey;
        this.sealer = sealer;
        this.sealedFields = sealedFields;
        this.entities = entities;
        this.datasets = datasets;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** Runs every check, one after the other, as the system. */
    public Mono<Report> verify() {
        RequestContext system = RequestContext.system(Locale.ENGLISH, REQUEST_PREFIX + clock.millis());
        // The last operation first: INTEGRITY_VERIFY records an operation of its own.
        return Flux.concat(lastOperation(), rekey ? keyRotation() : Mono.<Item>empty(), integrity(), mfaSecrets(),
                sealedSecrets(), storedFiles(),
                Flux.fromIterable(templateIds).concatMap(this::template))
            .collectList()
            .map(items -> new Report(clock.instant(), items))
            .contextWrite(view -> RequestContexts.put(view, system));
    }

    private StorageEngine engine() {
        return storages.getEngine(poolRef);
    }

    private Mono<Item> integrity() {
        return processes.run(IntegrityProcesses.VERIFY_PROCESS, new IntegrityProcesses.VerifyInput(null, null),
                ExecutionOptions.NONE)
            .map(result -> {
                IntegrityProcesses.VerifyOutput out = result.output();
                return new Item("integrity", out.intact(), "check " + out.checkNo() + ": " + out.sealCount()
                    + " blocks, " + out.rowCount() + " sealed rows, " + out.problemCount() + " problems, "
                    + out.unsealedCount() + " rows not sealed yet");
            })
            .onErrorResume(e -> Mono.just(new Item("integrity", false, "INTEGRITY_VERIFY failed: " + e.getMessage())));
    }

    /** The current version of every second factor opens with the configured key. */
    private Mono<Item> mfaSecrets() {
        return engine().select("""
                SELECT user_id, secret FROM (
                  SELECT DISTINCT ON (user_mfa_id) user_id, secret, is_deleted
                  FROM sec_user_mfa_version ORDER BY user_mfa_id, effect_start_time DESC, version_no DESC) current
                WHERE NOT is_deleted""", Map.of())
            .collectList()
            .map(rows -> {
                long unreadable = rows.stream().filter(row -> !opens(String.valueOf(row.get("secret")),
                    String.valueOf(row.get("user_id")))).count();
                return new Item("mfa-secrets", unreadable == 0, rows.size() + " second factors, " + unreadable
                    + " cannot be opened with JABIZ_MFA_KEY (key " + mfa.keyId() + ")");
            });
    }

    private boolean opens(String secret, String userId) {
        try {
            mfa.decrypt(secret, userId);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The rotation drill (decision D78): {@code KEY_ROTATION_REKEY} until nothing is left, as the job would over a few
     * runs, so that the checks after it see every value under the current key.
     */
    private Mono<Item> keyRotation() {
        int[] totals = new int[3];
        return Flux.range(0, MAX_REKEY_RUNS)
            .concatMap(run -> processes.run(KeyRotationProcesses.REKEY, new KeyRotationProcesses.RekeyInput(null),
                ExecutionOptions.NONE), 1)
            .map(result -> {
                KeyRotationProcesses.RekeyOutput out = result.output();
                totals[0] += out.mfaSecrets();
                totals[1] += out.sealedValues();
                totals[2] = out.unreadable();
                return out.more();
            })
            .takeUntil(more -> !more)
            .then(Mono.fromSupplier(() -> new Item("key-rotation", totals[2] == 0, totals[0] + " TOTP secrets and "
                + totals[1] + " sealed values stored under the current keys, " + totals[2] + " do not open")))
            .onErrorResume(e -> Mono.just(new Item("key-rotation", false, "KEY_ROTATION_REKEY failed: "
                + e.getMessage())));
    }

    /** Every value of a declared sealed field ({@link SealedFields}) opens with the configured seal keys. */
    private Mono<Item> sealedSecrets() {
        List<SealedField> fields = sealedFields.orderedStream().flatMap(bean -> bean.fields().stream()).toList();
        if (fields.isEmpty()) {
            return Mono.just(new Item("sealed-secrets", true, "no sealed fields declared"));
        }
        AtomicLong values = new AtomicLong();
        AtomicLong unreadable = new AtomicLong();
        AtomicLong cut = new AtomicLong();
        return Flux.fromIterable(fields).concatMap(field -> {
                EntityDefinition def = entities.getOrThrow(field.entity());
                DatasetDefinition dataset = datasets.findForEntity(field.entity()).orElseThrow();
                EntityQuery query = EntityQuery.builder().where(new QueryPredicate.IsNotNull(field.field()))
                    .limit(SEALED_ROWS + 1).build();
                return entityManager.queryAll(dataset, def, query).index().doOnNext(indexed -> {
                    if (indexed.getT1() >= SEALED_ROWS) {
                        cut.incrementAndGet();
                        return;
                    }
                    values.incrementAndGet();
                    Map<String, Object> row = indexed.getT2().attributes();
                    try {
                        // Without a key nothing opens: fine only while nothing is sealed.
                        sealer.open(String.valueOf(row.get(field.field())), field.context().apply(row));
                    } catch (RuntimeException e) {
                        unreadable.incrementAndGet();
                    }
                });
            })
            .then(Mono.fromSupplier(() -> new Item("sealed-secrets", unreadable.get() == 0, values.get()
                + " sealed values in " + fields.size() + " fields, " + unreadable.get() + " cannot be opened"
                + (sealer.configured() ? " with JABIZ_SEAL_KEY (key " + sealer.keyId() + ")" : " (no JABIZ_SEAL_KEY)")
                + (cut.get() > 0 ? "; fields with more than "
                + SEALED_ROWS + " rows checked in part" : ""))));
    }

    /** Every registered file's original and variants are in the store, the original at its recorded size. */
    private Mono<Item> storedFiles() {
        AtomicLong missing = new AtomicLong();
        AtomicLong wrongSize = new AtomicLong();
        return engine().select("SELECT file_id, size_bytes, variants FROM sys_file", Map.of())
            .flatMap(row -> {
                UUID id = (UUID) row.get("file_id");
                long size = ((Number) row.get("size_bytes")).longValue();
                Object variants = row.get("variants");
                List<String> names = new ArrayList<>(List.of(FileKeys.ORIGINAL));
                if (variants != null && !variants.toString().isBlank()) {
                    names.addAll(Arrays.asList(variants.toString().split(",")));
                }
                return Flux.fromIterable(names)
                    .concatMap(name -> files.size(FileKeys.key(id, name.strip()))
                        .map(stored -> {
                            if (FileKeys.ORIGINAL.equals(name) && stored != size) {
                                wrongSize.incrementAndGet();
                            }
                            return stored;
                        })
                        .switchIfEmpty(Mono.fromRunnable(missing::incrementAndGet)))
                    .then(Mono.just(1));
            }, FILE_CONCURRENCY)
            .count()
            .map(count -> new Item("files", missing.get() == 0 && wrongSize.get() == 0, count + " files, "
                + missing.get() + " objects missing from the store, " + wrongSize.get() + " originals of another size"));
    }

    /**
     * The last committed operation: with a target time, the restore must not reach past it, and the gap is the
     * recovery point (what the restore lost of the time before the target).
     */
    private Mono<Item> lastOperation() {
        return engine().select("SELECT max(op_time) AS last FROM op_process WHERE request_id IS NULL "
                + "OR request_id NOT LIKE '" + REQUEST_PREFIX + "%'", Map.of())
            .next()
            .map(row -> {
                Object value = row.get("last");
                Instant last = value instanceof OffsetDateTime time ? time.toInstant()
                    : value instanceof Instant instant ? instant : null;
                if (last == null) {
                    return new Item("last-operation", false, "no operation at all: is this the right database?");
                }
                if (targetTime == null) {
                    return new Item("last-operation", true, "last committed operation at " + last);
                }
                if (last.isAfter(targetTime)) {
                    return new Item("last-operation", false, "last committed operation at " + last
                        + " is after the target time " + targetTime + ": restored past it");
                }
                return new Item("last-operation", true, "last committed operation at " + last + ", "
                    + Duration.between(last, targetTime).toSeconds() + " s before the target time " + targetTime
                    + " (recovery point)");
            });
    }

    /** An application's check: a template that returns no rows when all is well. */
    private Mono<Item> template(String id) {
        String name = "template " + id;
        AdvancedQueryDefinition definition = templates.find(id).orElse(null);
        if (definition == null) {
            return Mono.just(new Item(name, false, "no such template"));
        }
        return queries.all(definition, Map.of(), AdvancedQueryExecutor.At.NOW, null, List.of(), TEMPLATE_ROWS + 1,
                true)
            .map(page -> {
                int rows = page.items().size();
                return new Item(name, rows == 0, rows == 0 ? "no rows"
                    : (rows > TEMPLATE_ROWS ? "more than " + TEMPLATE_ROWS : String.valueOf(rows)) + " rows");
            })
            .onErrorResume(e -> Mono.just(new Item(name, false, "failed: " + e.getMessage())));
    }
}
