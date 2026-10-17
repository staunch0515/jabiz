package com.jabiz.runtime.storage;

import org.springframework.stereotype.Component;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities' commits have their inserts written together (ROADMAP phase 16p, T6; decision D46 rule 1). All of
 * them, except an entity whose table has triggers besides the platform's own (append-only guard, ledger balance,
 * dictionary notification): such a trigger may rely on rows arriving one statement at a time, so those commits run
 * statement by statement. The startup check of the metamodel finds them ({@code METAMODEL}, a warning) and records
 * them here; with that check disabled, every entity is combined.
 */
@Component
public class WriteBatching {

    /** Trigger functions of the platform's migrations, which are indifferent to how many rows a statement inserts. */
    public static final Set<String> PLATFORM_TRIGGER_FUNCTIONS = Set.of("jabiz_reject_mutation",
        "jabiz_ledger_check_balance", "jabiz_notify_dict_changed");

    private final Set<String> excluded = ConcurrentHashMap.newKeySet();

    /** Context key of {@link #statementByStatement}. */
    private static final Object STATEMENT_BY_STATEMENT = new Object();

    /**
     * {@code context} in which commits write their rows one statement at a time, as before phase 16p: a hook for the
     * tests that compare both ways (the same audit records, the same constraints).
     */
    public static Context statementByStatement(Context context) {
        return context.put(STATEMENT_BY_STATEMENT, Boolean.TRUE);
    }

    /** Whether {@link #statementByStatement} applies in {@code context}. */
    public static boolean isStatementByStatement(ContextView context) {
        return context.hasKey(STATEMENT_BY_STATEMENT);
    }

    /** Records that the commits of {@code entityType} are not combined. */
    public void exclude(String entityType) {
        excluded.add(entityType.toLowerCase(Locale.ROOT));
    }

    /** Whether the commits of {@code entityType} have their inserts combined. */
    public boolean combines(String entityType) {
        return entityType != null && !excluded.contains(entityType.toLowerCase(Locale.ROOT));
    }
}
