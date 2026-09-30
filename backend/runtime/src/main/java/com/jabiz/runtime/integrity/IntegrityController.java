package com.jabiz.runtime.integrity;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.integrity.IntegrityKey;
import com.jabiz.integrity.IntegrityProblem;
import com.jabiz.runtime.EntityNotFoundException;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.storage.Rows;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The seals as they stand (docs/design/21-audit-retention.md section 2.4): the head of the chain - to be kept
 * outside the system as well -, the blocks and the verifications. Needs {@value IntegrityPermissions#READ}.
 * Sealing and verifying are the processes {@code INTEGRITY_SEAL} and {@code INTEGRITY_VERIFY}.
 */
@RestController
@RequestMapping("/api/integrity")
class IntegrityController {

    private static final int MAX_LIMIT = 500;

    /**
     * @param sealNo       the last block, or null before the first seal
     * @param currentKeyId the id of the key the application signs with now
     */
    record Head(Long sealNo, Instant sealedTime, Integer rowCount, String sealHash, String keyId,
        String currentKeyId) {}

    record Seal(long sealNo, Instant sealedTime, int rowCount, String merkleRoot, String prevHash, String sealHash,
        String keyId, Long processSeqId) {}

    record SealPage(List<Seal> items, long offset, int limit) {}

    /**
     * @param intact        whether nothing was found
     * @param problemCount  all problems found; {@code problems} of the detail holds the first ones
     * @param unsealedCount rows no block held yet when it ran
     */
    record CheckSummary(long checkNo, Instant checkedTime, String actorId, Long processSeqId, Long fromSeal,
        Long toSeal, int sealCount, long rowCount, long unsealedCount, int problemCount, boolean intact,
        String keyId) {}

    record CheckPage(List<CheckSummary> items, long offset, int limit) {}

    record CheckDetail(CheckSummary check, List<IntegrityProblem> problems) {}

    private final IntegrityStore store;
    private final IntegrityKey key;
    private final JsonMapper json;

    IntegrityController(IntegrityStore store, IntegrityKey key, JsonMapper json) {
        this.store = store;
        this.key = key;
        this.json = json;
    }

    @GetMapping("/head")
    Mono<Head> head() {
        return readable().then(store.head(store.engine())
            .map(stored -> new Head(stored.block().sealNo(), stored.block().sealedTime(), stored.block().rowCount(),
                stored.storedHash(), stored.block().keyId(), key.id()))
            .defaultIfEmpty(new Head(null, null, null, null, null, key.id())));
    }

    @GetMapping("/seals")
    Mono<SealPage> seals(@RequestParam(defaultValue = "0") long offset,
        @RequestParam(defaultValue = "50") int limit) {
        return readable().then(Mono.defer(() -> {
            validate(offset, limit);
            return store.sealPage(offset, limit)
                .map(row -> new Seal(Rows.longValue(row.get("seal_no")), Rows.instant(row.get("sealed_time")),
                    Rows.intValue(row.get("row_count")), Rows.string(row.get("merkle_root")),
                    Rows.string(row.get("prev_hash")), Rows.string(row.get("seal_hash")),
                    Rows.string(row.get("key_id")), Rows.longValue(row.get("process_seq_id"))))
                .collectList()
                .map(items -> new SealPage(items, offset, limit));
        }));
    }

    @GetMapping("/checks")
    Mono<CheckPage> checks(@RequestParam(defaultValue = "0") long offset,
        @RequestParam(defaultValue = "50") int limit) {
        return readable().then(Mono.defer(() -> {
            validate(offset, limit);
            return store.checkPage(offset, limit).map(IntegrityController::summary).collectList()
                .map(items -> new CheckPage(items, offset, limit));
        }));
    }

    @GetMapping("/checks/{checkNo}")
    Mono<CheckDetail> check(@PathVariable long checkNo) {
        return readable().then(store.check(checkNo)
            .switchIfEmpty(Mono.error(() -> new EntityNotFoundException("Integrity check " + checkNo + " not found")))
            .map(row -> new CheckDetail(summary(row), json.readValue(Rows.string(row.get("problems_json")),
                new TypeReference<List<IntegrityProblem>>() {}))));
    }

    private static CheckSummary summary(Map<String, Object> row) {
        int problems = Rows.intValue(row.get("problem_count"));
        return new CheckSummary(Rows.longValue(row.get("check_no")), Rows.instant(row.get("checked_time")),
            Rows.string(row.get("actor_id")), Rows.longValue(row.get("process_seq_id")),
            Rows.longValue(row.get("from_seal")), Rows.longValue(row.get("to_seal")),
            Rows.intValue(row.get("seal_count")), Rows.longValue(row.get("row_count")),
            Rows.longValue(row.get("unsealed_count")), problems, problems == 0, Rows.string(row.get("key_id")));
    }

    private static Mono<Void> readable() {
        return RequestContexts.current().flatMap(request -> request.hasPermission(IntegrityPermissions.READ)
            ? Mono.empty()
            : Mono.error(new PermissionDeniedException(IntegrityPermissions.READ,
                "Reading the integrity seals needs permission " + IntegrityPermissions.READ)));
    }

    private static void validate(long offset, int limit) {
        List<Violation> violations = new ArrayList<>();
        if (offset < 0) {
            violations.add(new Violation("offset", PlatformErrorCodes.INVALID_VALUE, "offset must not be negative"));
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            violations.add(new Violation("limit", PlatformErrorCodes.INVALID_VALUE,
                "limit must be between 1 and " + MAX_LIMIT));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
