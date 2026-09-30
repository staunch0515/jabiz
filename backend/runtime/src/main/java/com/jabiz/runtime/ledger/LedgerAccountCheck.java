package com.jabiz.runtime.ledger;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import com.jabiz.query.BoundValue;
import com.jabiz.runtime.BusinessRuleViolationException;
import com.jabiz.runtime.entity.FieldWriteCheck;
import com.jabiz.runtime.storage.StorageAdapterRegistry;
import com.jabiz.runtime.storage.StorageEngine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the account hierarchy sound on every write path (docs/design/11-ledger-events-jobs.md section 1.4; decision
 * D24): a parent is a summary account ({@code LEDGER_PARENT_NOT_SUMMARY}) that is not the account itself or one of
 * its sub-accounts ({@code LEDGER_ACCOUNT_CYCLE}); an account with postings does not become a summary account
 * ({@code LEDGER_SUMMARY_HAS_ENTRIES}) and one with sub-accounts stays one ({@code LEDGER_ACCOUNT_HAS_CHILDREN}).
 * Each version's latest state counts, a scheduled one included. Changes of the hierarchy hold the ledger's account
 * lock exclusively until their transaction ends, postings hold it shared ({@link #LOCK}), so a posting and an account
 * turning summary never pass each other.
 */
@Component
public class LedgerAccountCheck implements FieldWriteCheck {

    /** Key of the transaction-scoped advisory lock of the account hierarchy. */
    static final String LOCK = "jabiz.ledger.accounts";

    private static final String ACCOUNTS = "(SELECT DISTINCT ON (account_id) account_id, parent_id, summary, is_deleted"
        + " FROM ledger_account_version ORDER BY account_id, version_no DESC)";

    private final StorageAdapterRegistry storages;
    private final String poolRef;

    public LedgerAccountCheck(StorageAdapterRegistry storages,
        @Value("${jabiz.storage.default-pool-ref:default}") String poolRef) {
        this.storages = storages;
        this.poolRef = poolRef;
    }

    @Override
    public Mono<Void> verify(EntityDefinition def, Map<String, Object> values, Collection<String> changedFields) {
        return verify(def, values.get(def.primaryKey), values, changedFields);
    }

    @Override
    public Mono<Void> verify(EntityDefinition def, Object id, Map<String, Object> values,
        Collection<String> changedFields) {
        if (!def.name.equals(LedgerEntities.ACCOUNT)
            || !changedFields.contains("parentId") && !changedFields.contains("summary")) {
            return Mono.empty();
        }
        StorageEngine engine = storages.getEngine(poolRef);
        UUID self = id == null ? null : UUID.fromString(String.valueOf(id));
        Object code = values.get("accountCode") != null ? values.get("accountCode") : String.valueOf(id);
        Map<String, Object> params = Map.of("account", String.valueOf(code));
        List<Mono<Violation>> checks = new ArrayList<>();
        if (changedFields.contains("parentId") && values.get("parentId") instanceof UUID parent) {
            checks.add(exists(engine, "SELECT 1 AS found FROM " + ACCOUNTS + " a WHERE a.account_id = :parent"
                    + " AND a.summary IS TRUE", Map.of("parent", BoundValue.of(parent)))
                .filter(summary -> !summary)
                .map(notSummary -> new Violation("parentId", PlatformErrorCodes.LEDGER_PARENT_NOT_SUMMARY,
                    "The parent of account " + code + " is not a summary account", params)));
            if (self != null) {
                checks.add(exists(engine, "WITH RECURSIVE up(id) AS (SELECT CAST(:parent AS uuid)"
                        + " UNION SELECT a.parent_id FROM up JOIN " + ACCOUNTS + " a ON a.account_id = up.id"
                        + " WHERE a.parent_id IS NOT NULL) SELECT 1 AS found FROM up WHERE up.id = :self",
                        Map.of("parent", BoundValue.of(parent), "self", BoundValue.of(self)))
                    .filter(cycle -> cycle)
                    .map(cycle -> new Violation("parentId", PlatformErrorCodes.LEDGER_ACCOUNT_CYCLE,
                        "Account " + code + " would be under one of its own sub-accounts", params)));
            }
        }
        if (changedFields.contains("summary") && self != null) {
            if (Boolean.TRUE.equals(values.get("summary"))) {
                checks.add(exists(engine, "SELECT 1 AS found FROM ledger_entry_version WHERE account_id = :self"
                        + " LIMIT 1", Map.of("self", BoundValue.of(self)))
                    .filter(posted -> posted)
                    .map(posted -> new Violation("summary", PlatformErrorCodes.LEDGER_SUMMARY_HAS_ENTRIES,
                        "Account " + code + " has postings", params)));
            } else {
                checks.add(exists(engine, "SELECT 1 AS found FROM " + ACCOUNTS + " a WHERE a.parent_id = :self"
                        + " AND NOT a.is_deleted", Map.of("self", BoundValue.of(self)))
                    .filter(children -> children)
                    .map(children -> new Violation("summary", PlatformErrorCodes.LEDGER_ACCOUNT_HAS_CHILDREN,
                        "Account " + code + " has sub-accounts", params)));
            }
        }
        return lock(engine, false)
            .thenMany(reactor.core.publisher.Flux.concat(checks))
            .collectList()
            .flatMap(violations -> violations.isEmpty() ? Mono.<Void>empty()
                : Mono.error(new BusinessRuleViolationException(violations)));
    }

    /** Holds the account lock until the transaction ends: shared for postings, exclusive for hierarchy changes. */
    static Mono<Void> lock(StorageEngine engine, boolean shared) {
        return engine.select("SELECT 1 AS locked FROM (SELECT pg_advisory_xact_lock" + (shared ? "_shared" : "")
            + "(hashtextextended(:key, 0))) l", Map.of("key", BoundValue.of(LOCK))).then();
    }

    private static Mono<Boolean> exists(StorageEngine engine, String sql, Map<String, BoundValue> params) {
        return engine.select(sql, params).hasElements();
    }
}
