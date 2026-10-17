package com.jabiz.runtime.storage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities' commits have their inserts written together (ROADMAP phase 16p, T6; decision D46 rule 1). All of
 * them, except an entity whose table has triggers besides the platform's own (append-only guard, ledger balance,
 * dictionary notification): such a trigger may rely on rows arriving one statement at a time, so those commits run
 * statement by statement. The startup check of the metamodel finds them ({@code METAMODEL}, a warning) and records
 * them here; with that check disabled, every entity is combined.
 *
 * <p>With {@code jabiz.storage.write-batching.enabled} (the default, ROADMAP phase 16p-2, T9) the rows are held for the
 * whole transaction rather than one commit: they are written with the next statement that has to be sent anyway, or
 * on their own before a statement that reads or writes their tables, a savepoint, a blocking step and the commit.
 * {@code false} is the emergency switch back to phase 16p-1: each commit writes its rows when it ends.
 */
@Component
public class WriteBatching {

    private final boolean acrossTransaction;

    /** Defaults, as configured when nothing is: rows held for the whole transaction. */
    public WriteBatching() {
        this(true);
    }

    /** @param acrossTransaction whether rows are held for the whole transaction (T9), not only within one commit */
    @Autowired
    public WriteBatching(@Value("${jabiz.storage.write-batching.enabled:true}") boolean acrossTransaction) {
        this.acrossTransaction = acrossTransaction;
    }

    /** Whether rows are held for the whole transaction (phase 16p-2, T9) rather than only within one commit. */
    public boolean acrossTransaction() {
        return acrossTransaction;
    }

    /** Trigger functions of the platform's migrations, which are indifferent to how many rows a statement inserts. */
    public static final Set<String> PLATFORM_TRIGGER_FUNCTIONS = Set.of("jabiz_reject_mutation",
        "jabiz_ledger_check_balance", "jabiz_notify_dict_changed", "jabiz_dict_item_view_insert");

    private final Set<String> excluded = ConcurrentHashMap.newKeySet();
    private volatile boolean combinesAny = true;
    private volatile boolean reusesReads = true;

    /** Commits write every row one statement at a time: a table they all write has a trigger of its own. */
    public void combineNone() {
        combinesAny = false;
    }

    /**
     * Transactions do not rely on what they read (decision D46 rules 3 and 4): a trigger of the application may write
     * rows the platform does not see written, so a read may be out of date without the platform knowing.
     */
    public void stopReusingReads() {
        reusesReads = false;
    }

    private volatile boolean holdsShared = true;

    /**
     * The rows held only across a transaction (phase 16p-2: a sub-process's operation record, a result) are written as
     * they come: a table they go to has a trigger of its own. Unlike {@link #combineNone}, the commits of phase 16p-1
     * are combined as before.
     */
    public void stopHoldingShared() {
        holdsShared = false;
    }

    /**
     * Whether the rows a transaction holds besides its commits' (a sub-process's operation record, an event of
     * {@code PublishEvent}, a result; phase 16p-2) may be held: none of the tables they go to has a trigger of its own.
     */
    public boolean holdsShared() {
        return combinesAny && holdsShared;
    }

    /** Whether a transaction may rely on what it read and inserted (references, updates at a known version). */
    public boolean reusesReads() {
        return reusesReads;
    }

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

    private final Map<String, Set<String>> viewsOver = new ConcurrentHashMap<>();

    /**
     * Records that the view {@code view} reads {@code tables} (directly or through other views): a statement naming
     * the view reads rows held for those tables, which are therefore written before it (decision D46 rule 1).
     */
    public void view(String view, Collection<String> tables) {
        for (String table : tables) {
            viewsOver.computeIfAbsent(table.toLowerCase(Locale.ROOT), t -> ConcurrentHashMap.newKeySet())
                .add(view.toLowerCase(Locale.ROOT));
        }
    }

    /** The views of the schema known to read {@code table}, by lower-case name; none when nothing was recorded. */
    public Set<String> viewsOver(String table) {
        return Set.copyOf(viewsOver.getOrDefault(table.toLowerCase(Locale.ROOT), Set.of()));
    }

    /** Records that the commits of {@code entityType} are not combined. */
    public void exclude(String entityType) {
        excluded.add(entityType.toLowerCase(Locale.ROOT));
    }

    /** Whether the commits of {@code entityType} have their inserts combined. */
    public boolean combines(String entityType) {
        return combinesAny && entityType != null && !excluded.contains(entityType.toLowerCase(Locale.ROOT));
    }
}
