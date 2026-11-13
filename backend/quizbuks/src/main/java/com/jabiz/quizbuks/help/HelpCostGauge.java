package com.jabiz.quizbuks.help;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.StepSpec;
import com.jabiz.runtime.process.steps.RunTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * The steps that keep the dollar gauge {@value HelpUsageGauge#COST_NAME} right for a process outside the help (F-M10 review
 * M3): the reviewers' translation checks are paid by the platform and count towards the same alert, so the process that
 * finishes one of them reads the month's dollars (before its own write, as the help's outcome does) and leaves the share
 * with the job's cost added for {@link HelpUsageGauge}, which the process runs after the commit.
 */
public final class HelpCostGauge {

    static final String COSTS = "helpCostGaugeCosts";

    private HelpCostGauge() {}

    /** The step that reads the month's dollars when {@code when} holds; otherwise it sends no statement. */
    public static <C extends ProcessContext> StepSpec<RunTemplate.Metadata<C>, C> read(Predicate<C> when,
        Function<C, Instant> finished, ZoneId zone) {
        return RunTemplate.<C>when(when, HelpCosts.TEMPLATE,
            ctx -> HelpCosts.params(HelpCosts.monthOf(finished.apply(ctx), zone), zone), COSTS);
    }

    /** Puts the share of the alert the dollars read come to, and their month, where {@link HelpUsageGauge} takes them. */
    @SuppressWarnings("unchecked")
    public static void share(ProcessContext ctx, Instant finished, ZoneId zone, BigDecimal thisJobsCost) {
        // Not asked (the template did not run, or `when` was false and left an empty list), or no cost to add: this job
        // does not know the month's dollars, so the gauge stays where it is.
        if (!ctx.contains(COSTS) || thisJobsCost == null) {
            return;
        }
        List<Map<String, Object>> rows = (List<Map<String, Object>>) ctx.get(COSTS);
        if (rows == null || rows.isEmpty()) {
            return;
        }
        HelpCosts.Month month = HelpCosts.Month.of(rows.getFirst());
        // The dollars were read before this job's cost was written: its cost is added to them.
        BigDecimal total = month.total().add(thisJobsCost);
        ctx.put(HelpUsageGauge.COST_PERCENT, HelpCosts.percent(total, month.alert()));
        ctx.put(HelpUsageGauge.MONTH, HelpCosts.monthOf(finished, zone));
    }
}
