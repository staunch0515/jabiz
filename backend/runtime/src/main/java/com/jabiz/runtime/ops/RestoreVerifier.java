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
 * ({@code JABIZ_MFA_KEY}), every registered file is in the file store at its size, the time of the last committed
 * operation (against the target time of the restore, the recovery point), and the application's own checks: SQL
 * templates named in {@code jabiz.ops.verify-restore.templates} that return no rows when all is well. Nothing is
 * changed but the record {@code INTEGRITY_VERIFY} keeps of itself. Values are never reported, only counts.
 */
@Component
public class RestoreVerifier {

    static final String ENABLED = "jabiz.ops.verify-restore.enabled";
    static final String TEMPLATES = "jabiz.ops.verify-restore.templates";
    static final String TARGET_TIME = "jabiz.ops.verify-restore.target-time";
    /** The request id of a verification's own operations, which are not part of the restored data. */
    static final String REQUEST_PREFIX = "verify-restore-";
    /** Rows of an application check shown as found; more are counted as "more than". */
    static final int TEMPLATE_ROWS = 100;
    private static final int FILE_CONCURRENCY = 8;

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
    private final Clock clock;

    public RestoreVerifier(ProcessExecutor processes, MfaSecretCipher mfa, FileStore files,
        SqlTemplateRegistry templates, AdvancedQueryExecutor queries, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef,
        @Value("${" + TEMPLATES + ":}") String templateIds, @Value("${" + TARGET_TIME + ":}") String targetTime,
        Clock clock) {
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
        this.clock = clock;
    }

    /** Runs every check, one after the other, as the system. */
    public Mono<Report> verify() {
        RequestContext system = RequestContext.system(Locale.ENGLISH, REQUEST_PREFIX + clock.millis());
        // The last operation first: INTEGRITY_VERIFY records an operation of its own.
        return Flux.concat(lastOperation(), integrity(), mfaSecrets(), storedFiles(),
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
