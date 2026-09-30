package com.jabiz.runtime.accessreview;

import com.jabiz.query.BoundValue;
import com.jabiz.runtime.audit.AuditService;
import com.jabiz.runtime.security.SecurityEntities;
import com.jabiz.runtime.sod.SodService;
import com.jabiz.runtime.storage.Rows;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The parts of an access review (docs/design/10-security.md section 13.3, decision D28 item 9) besides the access
 * report, which is an issued report ({@code REPORT_ISSUE} of {@value #ACCESS_TEMPLATE}): the security changes of the
 * period from the audit trail, the segregation-of-duties conflicts, and the signed reviews in
 * {@code sys_access_review}, which keep references and hashes only.
 */
@Component
public class AccessReviews {

    /** The access report: users, roles, data periods, permissions and last sign-in as of a time. */
    public static final String ACCESS_TEMPLATE = "jabiz.security.access_review";

    /** The entities whose changes are changes of access. */
    public static final List<String> SECURITY_ENTITIES = List.of(SecurityEntities.USER, SecurityEntities.ROLE,
        SecurityEntities.ROLE_PERMISSION, SecurityEntities.USER_ROLE, SecurityEntities.USER_IDENTITY,
        SecurityEntities.USER_MFA);

    static final String TABLE = "sys_access_review";

    /** The security changes of a period, as the review covers them, and their hash. */
    public record Changes(List<AuditService.AuditRecordEntry> items, String hash) {}

    /** The conflict report at a moment, and its hash. */
    public record Conflicts(List<SodService.Conflict> items, String hash) {}

    /** A signed review as the API shows it. */
    public record Review(UUID reviewId, Instant periodFrom, Instant periodTo, UUID reportRunId, String reportHash,
        int changesCount, String changesHash, List<SodService.Conflict> conflicts, String conflictsHash,
        String reviewer, String comment, Instant signedAt, long processSeqId) {}

    private final AuditService audit;
    private final SodService sod;
    private final StorageAdapterRegistry storages;
    private final String poolRef;
    private final JsonMapper json;

    public AccessReviews(AuditService audit, SodService sod, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef, JsonMapper json) {
        this.audit = audit;
        this.sod = sod;
        this.storages = storages;
        this.poolRef = poolRef;
        this.json = json;
    }

    /** The audit records of the security entities recorded in {@code [from, to)}, oldest first. */
    public Mono<Changes> changes(Instant from, Instant to) {
        return audit.recordsOf(SECURITY_ENTITIES, from, to)
            .map(items -> new Changes(items, hash(items)));
    }

    /** The segregation-of-duties conflicts now. */
    public Mono<Conflicts> conflicts() {
        return sod.conflicts().map(items -> new Conflicts(items, hash(items)));
    }

    Mono<Void> insert(Review review) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("review_id", review.reviewId());
        row.put("period_from", review.periodFrom());
        row.put("period_to", review.periodTo());
        row.put("report_run_id", review.reportRunId());
        row.put("report_hash", review.reportHash());
        row.put("changes_count", review.changesCount());
        row.put("changes_hash", review.changesHash());
        row.put("conflicts", json.writeValueAsString(review.conflicts()));
        row.put("conflicts_count", review.conflicts().size());
        row.put("conflicts_hash", review.conflictsHash());
        row.put("reviewer", review.reviewer());
        row.put("review_comment", review.comment());
        row.put("signed_at", review.signedAt());
        row.put("process_seq_id", review.processSeqId());
        return storages.getEngine(poolRef).insert(TABLE, row);
    }

    /** The signed reviews, the latest period first, at most {@code limit}. */
    public Mono<List<Review>> list(int limit) {
        StorageEngine engine = storages.getEngine(poolRef);
        return engine.select("SELECT * FROM " + TABLE + " ORDER BY period_to DESC, signed_at DESC LIMIT :limit",
                Map.of("limit", BoundValue.of((long) limit)))
            .map(this::review)
            .collectList();
    }

    private Review review(Map<String, Object> row) {
        List<SodService.Conflict> conflicts = List.of(json.readValue(Rows.string(row.get("conflicts")),
            SodService.Conflict[].class));
        return new Review((UUID) row.get("review_id"), Rows.instant(row.get("period_from")),
            Rows.instant(row.get("period_to")), (UUID) row.get("report_run_id"), Rows.string(row.get("report_hash")),
            Rows.longValue(row.get("changes_count")).intValue(), Rows.string(row.get("changes_hash")), conflicts,
            Rows.string(row.get("conflicts_hash")), Rows.string(row.get("reviewer")),
            Rows.string(row.get("review_comment")), Rows.instant(row.get("signed_at")),
            Rows.longValue(row.get("process_seq_id")));
    }

    /** SHA-256 of the value's JSON: the same records or conflicts give the same hash. */
    private String hash(Object value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(json.writeValueAsString(value)
                .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
