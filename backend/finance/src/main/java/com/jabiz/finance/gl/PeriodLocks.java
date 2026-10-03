package com.jabiz.finance.gl;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinitionBuilder;
import com.jabiz.process.StepSpec;
import com.jabiz.query.EntityQuery;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.QueryEntities;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Postings into a period and the changes that close it do not overlap (ROADMAP F11b; docs/design/06-process.md
 * section 4.1 of the platform). Every posting holds a shared lock on each period its day falls in, taken after a first
 * read of those periods and followed by a second read, which the checks then use; closing a period (or soft-closing
 * it, or closing it for a subledger, or closing the year in period 13) holds that period's lock alone. A posting that
 * read the period open therefore commits before the close reads the books, and one that comes after the close finds
 * the period closed: no posting slips into a closed period.
 *
 * <p>Closing a period, closing the year and deciding a reopening each check other periods of the year too (no later
 * period closed, the months closed before the year): they hold the fiscal year's lock alone, before any period's, so
 * they run one after the other. Postings never take it. One order is not kept: the year close holds period 13's lock
 * before its closing entry takes the shared locks of periods 12 and 13 (its day is in both). A posting of the same day
 * waiting for period 13 while a soft close of period 12 waits in between makes a cycle the database resolves, by
 * reordering the waits or by failing one of them (409, to be retried); year closes are rare enough for that.
 */
public final class PeriodLocks {

    /** The most periods one day falls in (a month, period 13, the opening): {@code JournalProcesses.periodsOf}. */
    static final int CANDIDATES = 4;

    static String name(String periodKey) {
        return "fin.period:" + periodKey;
    }

    /**
     * Shared locks on the periods read into {@link JournalProcesses#PERIODS}, in the order of their keys (the same
     * everywhere, so that two postings never wait for each other), then {@code query} read into it again.
     */
    static <I, O> ProcessDefinitionBuilder<I, O, ProcessContext> share(ProcessDefinitionBuilder<I, O, ProcessContext> pb,
        Function<ProcessContext, EntityQuery> query) {
        for (int i = 0; i < CANDIDATES; i++) {
            int position = i;
            pb.step("Share the lock of period " + (i + 1), HoldLock.<ProcessContext>shared(ctx -> nth(ctx, position)));
        }
        return pb.step("Load the period again", QueryEntities.of(GlEntities.PERIOD_DATASET, query,
            JournalProcesses.PERIODS));
    }

    /** The lock of the period {@code periodKey(ctx)} alone; none when there is no key. */
    public static StepSpec<HoldLock.Metadata<ProcessContext>, ProcessContext> exclusive(
        Function<ProcessContext, String> periodKey) {
        return HoldLock.exclusive(ctx -> {
            String key = periodKey.apply(ctx);
            return key == null || key.isBlank() ? null : name(key.trim());
        });
    }

    /** The lock of the fiscal year of the period {@code periodKey(ctx)} (its key's year), alone. */
    public static StepSpec<HoldLock.Metadata<ProcessContext>, ProcessContext> year(
        Function<ProcessContext, String> periodKey) {
        return HoldLock.exclusive(ctx -> {
            String key = periodKey.apply(ctx);
            int dash = key == null ? -1 : key.trim().indexOf('-');
            return dash <= 0 ? null : "fin.year:" + key.trim().substring(0, dash);
        });
    }

    private static String nth(ProcessContext ctx, int position) {
        List<String> keys = (ctx.contains(JournalProcesses.PERIODS) ? AccountProcesses.list(ctx,
            JournalProcesses.PERIODS) : List.<EntityInstance>of()).stream()
            .map(p -> (String) p.get("periodKey")).filter(Objects::nonNull).distinct().sorted().toList();
        return position < keys.size() ? name(keys.get(position)) : null;
    }

    private PeriodLocks() {}
}
