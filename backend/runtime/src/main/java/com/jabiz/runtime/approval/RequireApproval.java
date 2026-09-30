package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalEvaluation;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.approval.ContentHash;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.StepHandler;
import com.jabiz.runtime.process.steps.CheckedStep;
import com.jabiz.runtime.process.steps.EventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides whether a case of an approval subject needs approval and puts the {@link ApprovalOutcome} into the context
 * (docs/design/18-numbering-approvals-tasks.md section 3.3):
 * <ul>
 *   <li>a request of the case with the same content hash was approved → {@code APPROVED};</li>
 *   <li>one with the same content hash is pending → {@code PENDING} (the same request);</li>
 *   <li>otherwise the enabled rules of the subject in effect at the case's business time are evaluated: none
 *       applies (or the one that applies has no levels) → {@code NOT_REQUIRED}; else a request is made (preparer,
 *       rule version, content hash, levels) and {@code jabiz.approval.requested} published → {@code PENDING}.</li>
 * </ul>
 * Requests of the case whose content hash differs (the document changed since) are superseded. Every evaluation is
 * recorded ({@code ApprovalEvaluation}) with the rule versions considered and the facts. Processes evaluating the same
 * case wait for each other (a transaction-scoped advisory lock), so a case never gets two pending requests.
 */
@Component
public class RequireApproval<C extends ProcessContext> implements StepHandler<RequireApproval.Metadata<C>, C>,
    CheckedStep<RequireApproval.Metadata<C>> {

    /** Published when a request is made; the payload names the request, subject, document and preparer. */
    public static final String REQUESTED = "jabiz.approval.requested";

    /** @param when whether to evaluate at all (null: always) */
    public record Metadata<C>(String subject, Function<C, ApprovalCase> caseOf, String targetKey, Predicate<C> when) {
        public Metadata {
            Objects.requireNonNull(subject, "subject must not be null");
            Objects.requireNonNull(caseOf, "caseOf must not be null");
            Objects.requireNonNull(targetKey, "targetKey must not be null");
        }
    }

    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> of(String subject,
        Function<C, ApprovalCase> caseOf, String targetKey) {
        return StepSpec.of(RequireApproval.class, new Metadata<>(subject, caseOf, targetKey, null));
    }

    /** As {@link #of} when {@code condition(ctx)} holds; otherwise nothing is evaluated and the key stays unset. */
    public static <C extends ProcessContext> StepSpec<Metadata<C>, C> when(Predicate<C> condition, String subject,
        Function<C, ApprovalCase> caseOf, String targetKey) {
        return StepSpec.of(RequireApproval.class,
            new Metadata<>(subject, caseOf, targetKey, Objects.requireNonNull(condition)));
    }

    /** Payload of {@link #REQUESTED}. */
    public record Requested(String requestId, String subject, String entityId, String preparerId, int levels) {}

    private final ApprovalSubjectRegistry subjects;
    private final ApprovalStore store;
    private final ObjectProvider<EventPublisher> publisher;

    public RequireApproval(ApprovalSubjectRegistry subjects, ApprovalStore store,
        ObjectProvider<EventPublisher> publisher) {
        this.subjects = subjects;
        this.store = store;
        this.publisher = publisher;
    }

    @Override
    public Mono<Void> execute(Metadata<C> metadata, C ctx) {
        return Mono.defer(() -> {
            if (metadata.when() != null && !metadata.when().test(ctx)) {
                return Mono.empty();
            }
            ApprovalSubject subject = subjects.find(metadata.subject()).orElseThrow(() ->
                new IllegalStateException("Approval subject " + metadata.subject() + " is not declared"));
            ApprovalCase approvalCase = Objects.requireNonNull(metadata.caseOf().apply(ctx), "case must not be null");
            Map<String, Object> facts = subject.normalizeFacts(approvalCase.facts());
            String hash = ContentHash.of(approvalCase.content());
            Instant businessTime = approvalCase.businessTime() == null ? ctx.opTime() : approvalCase.businessTime();
            String preparer = approvalCase.preparerId() == null ? ctx.request().actorId()
                : approvalCase.preparerId();
            return store.lockCase(subject.name(), approvalCase.entityId())
                .then(store.openRequests(subject.name(), approvalCase.entityId())).flatMap(open -> {
                EntityInstance same = null;
                for (EntityInstance request : open) {
                    if (hash.equals(request.get("contentHash"))
                        && (same == null || ApprovalEntities.APPROVED.equals(request.get("status")))) {
                        same = request;
                    }
                }
                for (EntityInstance request : open) {
                    if (!hash.equals(request.get("contentHash"))) {
                        ctx.changes().update(ApprovalEntities.REQUEST, request.id(), request.version(),
                            Map.of("status", ApprovalEntities.SUPERSEDED));
                    }
                }
                if (same != null) {
                    ApprovalOutcome.Status status = ApprovalEntities.APPROVED.equals(same.get("status"))
                        ? ApprovalOutcome.Status.APPROVED : ApprovalOutcome.Status.PENDING;
                    String requestId = String.valueOf(same.id());
                    record(ctx, subject, approvalCase, status, null, List.of(), requestId, hash, facts, businessTime);
                    ctx.put(metadata.targetKey(), new ApprovalOutcome(status, requestId));
                    return Mono.<Void>empty();
                }
                return store.rules(subject, businessTime).flatMap(rules -> {
                    ApprovalEvaluation evaluation = ApprovalEvaluation.evaluate(rules, facts);
                    String matched = evaluation.matched() == null ? null : evaluation.matched().versionKey();
                    if (!evaluation.required()) {
                        record(ctx, subject, approvalCase, ApprovalOutcome.Status.NOT_REQUIRED, matched,
                            evaluation.versionKeys(), null, hash, facts, businessTime);
                        ctx.put(metadata.targetKey(), new ApprovalOutcome(ApprovalOutcome.Status.NOT_REQUIRED, null));
                        return Mono.<Void>empty();
                    }
                    List<Map<String, Object>> levels = new ArrayList<>();
                    evaluation.levels().forEach(level -> {
                        Map<String, Object> json = new LinkedHashMap<>();
                        json.put("permission", level.permission());
                        if (level.limitFact() != null) {
                            json.put("limitFact", level.limitFact());
                        }
                        levels.add(json);
                    });
                    Map<String, Object> request = new LinkedHashMap<>();
                    request.put("subject", subject.name());
                    request.put("entityId", approvalCase.entityId());
                    request.put("status", ApprovalEntities.PENDING);
                    request.put("preparerId", preparer);
                    request.put("ruleId", evaluation.matched().ruleId());
                    request.put("ruleVersionNo", BigDecimal.valueOf(evaluation.matched().versionNo()));
                    request.put("contentHash", hash);
                    request.put("levels", ApprovalJson.write(levels));
                    request.put("currentLevel", BigDecimal.ONE);
                    request.put("facts", ApprovalJson.write(facts));
                    String requestId = String.valueOf(ctx.changes().insert(ApprovalEntities.REQUEST, request));
                    record(ctx, subject, approvalCase, ApprovalOutcome.Status.PENDING, matched,
                        evaluation.versionKeys(), requestId, hash, facts, businessTime);
                    ctx.put(metadata.targetKey(), new ApprovalOutcome(ApprovalOutcome.Status.PENDING, requestId));
                    return publisher().publish(REQUESTED, new Requested(requestId, subject.name(),
                        approvalCase.entityId(), preparer, levels.size()), ctx.processSeqId());
                });
            });
        });
    }

    private EventPublisher publisher() {
        EventPublisher target = publisher.getIfUnique();
        if (target == null) {
            throw new IllegalStateException("No EventPublisher is configured");
        }
        return target;
    }

    private static void record(ProcessContext ctx, ApprovalSubject subject, ApprovalCase approvalCase,
        ApprovalOutcome.Status outcome, String matched, List<String> versions, String requestId, String hash,
        Map<String, Object> facts, Instant businessTime) {
        Map<String, Object> evaluation = new LinkedHashMap<>();
        evaluation.put("subject", subject.name());
        evaluation.put("entityId", approvalCase.entityId());
        evaluation.put("outcome", outcome.name());
        evaluation.put("matchedRule", matched);
        evaluation.put("ruleVersions", ApprovalJson.write(versions));
        evaluation.put("requestId", requestId);
        evaluation.put("contentHash", hash);
        evaluation.put("facts", ApprovalJson.write(facts));
        evaluation.put("businessTime", businessTime);
        evaluation.put("evaluatedTime", ctx.opTime());
        evaluation.put("processSeqId", BigDecimal.valueOf(ctx.processSeqId()));
        ctx.changes().insert(ApprovalEntities.EVALUATION, evaluation);
    }

    @Override
    public List<String> problems(Metadata<C> metadata) {
        List<String> problems = new ArrayList<>();
        if (subjects.find(metadata.subject()).isEmpty()) {
            problems.add("approval subject " + metadata.subject() + " is not declared");
        }
        if (publisher.getIfUnique() == null) {
            problems.add("requests approvals but no EventPublisher is configured");
        }
        return problems;
    }
}
