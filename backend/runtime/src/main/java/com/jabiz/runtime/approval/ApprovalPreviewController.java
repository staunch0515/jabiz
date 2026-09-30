package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalCondition;
import com.jabiz.approval.ApprovalEvaluation;
import com.jabiz.approval.ApprovalLevel;
import com.jabiz.approval.ApprovalRule;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.security.Permissions;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Impact preview of a draft approval rule (docs/design/18-numbering-approvals-tasks.md section 3.6): the latest
 * recorded facts of each case of the subject are evaluated with the rules in effect now and with the draft in place
 * (replacing {@code ruleId}, if given; a missing draft previews deleting that rule). The answer lists the cases whose
 * outcome changes (approval needed or not, or other levels) and how many were evaluated. Needs {@value ApprovalPermissions#READ}.
 */
@RestController
@RequestMapping("/api/approvals")
class ApprovalPreviewController {

    /** Most cases one preview evaluates. */
    static final int MAX_CASES = 5000;

    /**
     * @param condition the draft's condition as JSON (an object)
     * @param levels    the draft's levels as JSON (a list)
     */
    record DraftRule(String ruleCode, Object condition, Object levels, Integer priority, Boolean enabled) {}

    record PreviewRequest(String subject, String ruleId, DraftRule draft, Integer limit) {}

    /**
     * The finding for one case: whether approval is needed, by which rule (its code) and the permissions of its
     * levels. A case counts as changed when approval or the levels differ, not when another rule finds the same.
     */
    record Finding(boolean required, String ruleCode, List<String> levels) {

        boolean sameOutcome(Finding other) {
            return required == other.required && levels.equals(other.levels);
        }
    }

    record Change(String entityId, Instant evaluatedTime, Finding before, Finding after) {}

    record PreviewResponse(int evaluated, boolean truncated, List<Change> changed) {}

    private final ApprovalSubjectRegistry subjects;
    private final ApprovalStore store;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    ApprovalPreviewController(ApprovalSubjectRegistry subjects, ApprovalStore store, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.subjects = subjects;
        this.store = store;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @PostMapping("/preview")
    Mono<PreviewResponse> preview(@RequestBody PreviewRequest body) {
        return RequestContexts.current().flatMap(request -> {
            Permissions.require(request, ApprovalPermissions.READ, "Previewing approval rules");
            ApprovalSubject subject = subjects.find(body.subject()).orElseThrow(() -> invalid("subject",
                "approval subject '" + body.subject() + "' is not declared"));
            ApprovalRule draft = draft(body.draft(), subject);
            int limit = body.limit() == null ? MAX_CASES : Math.clamp(body.limit(), 1, MAX_CASES);
            return store.rules(subject, null).zipWith(latestFacts(subject.name(), limit + 1)).map(found -> {
                List<ApprovalRule> before = found.getT1();
                List<ApprovalRule> after = new ArrayList<>(before.stream()
                    .filter(rule -> !rule.ruleId().equals(body.ruleId())).toList());
                if (draft != null) {
                    after.add(draft);
                }
                List<Map<String, Object>> cases = found.getT2();
                boolean truncated = cases.size() > limit;
                List<Change> changed = new ArrayList<>();
                for (Map<String, Object> row : truncated ? cases.subList(0, limit) : cases) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> facts = subject.normalizeFacts(
                        (Map<String, Object>) Objects.requireNonNullElse(ApprovalJson.read((String) row.get("facts")),
                            Map.of()));
                    Finding was = finding(ApprovalEvaluation.evaluate(before, facts));
                    Finding will = finding(ApprovalEvaluation.evaluate(after, facts));
                    if (!was.sameOutcome(will)) {
                        changed.add(new Change((String) row.get("entity_id"), instant(row.get("evaluated_time")),
                            was, will));
                    }
                }
                return new PreviewResponse(truncated ? limit : cases.size(), truncated, changed);
            });
        });
    }

    private static Instant instant(Object value) {
        return value instanceof java.time.OffsetDateTime time ? time.toInstant() : (Instant) value;
    }

    private static Finding finding(ApprovalEvaluation evaluation) {
        return new Finding(evaluation.required(),
            evaluation.matched() == null ? null : evaluation.matched().ruleCode(),
            evaluation.levels().stream().map(ApprovalLevel::permission).toList());
    }

    private ApprovalRule draft(DraftRule draft, ApprovalSubject subject) {
        if (draft == null || Boolean.FALSE.equals(draft.enabled())) {
            return null;
        }
        List<String> problems = new ArrayList<>();
        ApprovalCondition condition = null;
        List<ApprovalLevel> levels = List.of();
        try {
            condition = ApprovalCondition.parse(draft.condition(), subject);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        try {
            levels = ApprovalLevel.parse(draft.levels(), subject);
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
        }
        if (!problems.isEmpty()) {
            throw invalid("draft", String.join("; ", problems));
        }
        return new ApprovalRule("draft", 0, draft.ruleCode() == null ? "draft" : draft.ruleCode(), condition,
            levels, draft.priority() == null ? 0 : draft.priority());
    }

    private Mono<List<Map<String, Object>>> latestFacts(String subject, int limit) {
        return storages.getEngine(poolRef).select("SELECT entity_id, facts, evaluated_time FROM ("
                + "SELECT DISTINCT ON (entity_id) entity_id, facts, evaluated_time FROM sys_approval_evaluation"
                + " WHERE subject = :subject ORDER BY entity_id, evaluated_time DESC, evaluation_id DESC) e"
                + " ORDER BY evaluated_time DESC, entity_id LIMIT :limit",
                Map.of("subject", BoundValue.of(subject), "limit", BoundValue.of(limit)))
            .collectList();
    }

    private static ValidationException invalid(String field, String detail) {
        return new ValidationException(List.of(new Violation(field, PlatformErrorCodes.CONTROL_CHANGE_INVALID,
            "Invalid draft: " + detail, Map.of("detail", detail))));
    }
}
