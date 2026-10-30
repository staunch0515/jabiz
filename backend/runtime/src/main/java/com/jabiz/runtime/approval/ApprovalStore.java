package com.jabiz.runtime.approval;

import com.jabiz.approval.ApprovalCondition;
import com.jabiz.approval.ApprovalLevel;
import com.jabiz.approval.ApprovalRule;
import com.jabiz.approval.ApprovalSubject;
import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.query.EntityQuery;
import com.jabiz.query.QueryPredicate;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.context.RequestContexts;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.query.BoundValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Reads of the approval entities shared by the steps, processes and endpoints of approvals. */
@Component
public class ApprovalStore {

    private final DatasetEntityManager entities;
    private final DatasetRegistry datasets;
    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public ApprovalStore(DatasetEntityManager entities, DatasetRegistry datasets, StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.entities = entities;
        this.datasets = datasets;
        this.storages = storages;
        this.poolRef = poolRef;
    }

    /** Holds the case's advisory lock until the transaction ends. */
    Mono<Void> lockCase(String subject, String entityId) {
        return storages.getEngine(poolRef)
            .select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) l",
                Map.of("key", BoundValue.of("jabiz.approval:" + subject + ":" + entityId)))
            .then();
    }

    /**
     * Refuses a session of an entry scope that would change the open requests of a case it does not own (decision
     * D64): requests of another scope, or of none, are not its to supersede or withdraw (422
     * {@code APPROVAL_OTHER_SCOPE}). Sessions without a scope change any.
     */
    static void requireOwnScope(com.jabiz.process.ProcessContext ctx, List<EntityInstance> open) {
        com.jabiz.context.EntryScope mine = ctx.request().entryScope();
        if (mine == null) {
            return;
        }
        for (EntityInstance request : open) {
            if (!mine.name().equals(request.get("scopeName")) || !mine.value().equals(request.get("scopeValue"))) {
                throw new com.jabiz.runtime.BusinessRuleViolationException(new com.jabiz.entity.Violation(null,
                    com.jabiz.i18n.PlatformErrorCodes.APPROVAL_OTHER_SCOPE, "The case has an open request of another "
                    + "entry scope", Map.of()));
            }
        }
    }

    /** The requests of a case that are pending or approved. */
    Mono<List<EntityInstance>> openRequests(String subject, String entityId) {
        return query(ApprovalEntities.REQUEST_DATASET, ApprovalEntities.APPROVAL_REQUEST, new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("subject", subject),
            new QueryPredicate.Eq("entityId", entityId),
            new QueryPredicate.In("status", List.of(ApprovalEntities.PENDING, ApprovalEntities.APPROVED)))), null);
    }

    /** The enabled rules of the subject in effect at {@code asOf}, parsed; a stored rule that no longer parses fails. */
    Mono<List<ApprovalRule>> rules(ApprovalSubject subject, Instant asOf) {
        return query(ApprovalEntities.RULE_DATASET, ApprovalEntities.APPROVAL_RULE, new QueryPredicate.And(List.of(
            new QueryPredicate.Eq("subject", subject.name()),
            new QueryPredicate.Eq("enabled", true))), asOf)
            .map(rows -> rows.stream().map(row -> rule(subject, row)).toList());
    }

    /** A stored rule as the evaluation sees it. */
    static ApprovalRule rule(ApprovalSubject subject, EntityInstance row) {
        String code = row.get("ruleCode");
        try {
            return new ApprovalRule(String.valueOf(row.id()), row.version(), code,
                ApprovalCondition.parse(ApprovalJson.read(row.get("condition")), subject),
                ApprovalLevel.parse(ApprovalJson.read(row.get("levels")), subject),
                ((BigDecimal) row.get("priority")).intValueExact());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Approval rule " + code + " does not fit approval subject "
                + subject.name() + ": " + e.getMessage(), e);
        }
    }

    /** The decisions taken on a request. */
    Mono<List<EntityInstance>> decisions(Object requestId) {
        return query(ApprovalEntities.DECISION_DATASET, ApprovalEntities.APPROVAL_DECISION,
            new QueryPredicate.Eq("requestId", requestId), null);
    }

    private Mono<List<EntityInstance>> query(String datasetId, EntityDefinition def, QueryPredicate where,
        Instant asOf) {
        DatasetDefinition dataset = datasets.findById(datasetId).orElseThrow();
        EntityQuery query = EntityQuery.builder().where(where).limit(ApprovalEntities.MAX_ROWS).build();
        // The platform's own reads of a case: whole, whatever the caller's entry scope (decision D64), so that one
        // open request a case stays the rule.
        return RequestContexts.withoutEntryScope(entities.query(dataset, def, query, asOf, null)).collectList()
            .map(rows -> {
                if (rows.size() >= ApprovalEntities.MAX_ROWS) {
                    throw new IllegalStateException(def.name + " query reached " + ApprovalEntities.MAX_ROWS
                        + " rows");
                }
                return List.copyOf(rows);
            });
    }
}
