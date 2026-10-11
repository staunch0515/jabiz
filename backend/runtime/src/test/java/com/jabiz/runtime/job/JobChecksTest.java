package com.jabiz.runtime.job;

import com.jabiz.job.JobDefinition;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.check.CheckProblem;
import com.jabiz.runtime.process.ProcessRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.ResolvableType;

import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.MaskStyle;
import com.jabiz.entity.SemanticKind;
import com.jabiz.job.JobItems;
import com.jabiz.query.custom.AdvancedQueryDefinition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class JobChecksTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> REGISTERED = ProcessDefinition.single("TICK", 1,
        In.class, In.class, (in, ctx) -> in);
    static final ProcessDefinition<In, In, ProcessContext> UNREGISTERED = ProcessDefinition.single("OTHER", 1,
        In.class, In.class, (in, ctx) -> in);

    static final ProcessDefinition<In, In, ProcessContext> ITEM = ProcessDefinition.single("ITEM", 1,
        In.class, In.class, (in, ctx) -> in).asInternal();
    static final ProcessDefinition<In, In, ProcessContext> NOT_INTERNAL = ProcessDefinition.single("ITEM_OPEN", 1,
        In.class, In.class, (in, ctx) -> in);

    static final AdvancedQueryDefinition SELECTION = AdvancedQueryDefinition.define("it.job.due", q -> q
        .fromEntities("Ticket")
        .parameter("now", new SemanticKind.Bool(), true)
        .parameterWithDefault("limitDays", new SemanticKind.Bool(), true)
        .returns("item_id", new SemanticKind.Text(36, false))
        .list(l -> l.sorts("item_id").defaultSort("item_id", true).key("item_id"))
        .permissions("it.read")
        .sqlTemplate("SELECT 1"));

    static final AdvancedQueryDefinition UNSORTED = AdvancedQueryDefinition.define("it.job.unsorted", q -> q
        .fromEntities("Ticket")
        .returns("item_id", new SemanticKind.Text(36, false))
        .publicAccess()
        .sqlTemplate("SELECT 1"));

    static final EntityDefinition TICKET = EntityDefinition.define("Ticket", eb -> {
        eb.physicalTable("ticket");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("id").required(true).asSemanticIdentity("urn:ticket"));
        eb.field("taxId", f -> f.physicalColumn("tax_id").asText(20).masked("ticket.tax", MaskStyle.TAX_ID));
    });

    /** Keyed by a masked field (an error), and naming it besides (a warning). */
    static final AdvancedQueryDefinition MASKED_KEY = AdvancedQueryDefinition.define("it.job.masked", q -> q
        .fromEntities("Ticket")
        .returnsFrom("item_id", "Ticket", "taxId")
        .list(l -> l.sorts("item_id").defaultSort("item_id", true).key("item_id"))
        .permissions("it.read")
        .sqlTemplate("SELECT t.{{Ticket.taxId}} AS item_id FROM {{Ticket}} t"));

    /** Keyed by the identity, but filtering on a masked field (a warning). */
    static final AdvancedQueryDefinition MASKED_FILTER = AdvancedQueryDefinition.define("it.job.filtered", q -> q
        .fromEntities("Ticket")
        .returnsFrom("item_id", "Ticket", "id")
        .list(l -> l.sorts("item_id").defaultSort("item_id", true).key("item_id"))
        .permissions("it.read")
        .sqlTemplate("SELECT t.{{Ticket.id}} AS item_id FROM {{Ticket}} t WHERE t.{{Ticket.taxId}} IS NULL"));

    private static final Map<String, AdvancedQueryDefinition> TEMPLATES = Map.of(SELECTION.queryId(), SELECTION,
        UNSORTED.queryId(), UNSORTED, MASKED_KEY.queryId(), MASKED_KEY, MASKED_FILTER.queryId(), MASKED_FILTER);

    private static List<CheckProblem> check(JobDefinition<?>... jobs) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("tick", REGISTERED);
        beans.addBean("item", ITEM);
        beans.addBean("itemOpen", NOT_INTERNAL);
        for (int i = 0; i < jobs.length; i++) {
            beans.addBean("job" + i, jobs[i]);
        }
        ProcessRegistry processes = new ProcessRegistry(
            beans.getBeanProvider(ResolvableType.forClass(ProcessDefinition.class)));
        JobRegistry registry = new JobRegistry(beans.getBeanProvider(ResolvableType.forClass(JobDefinition.class)));
        return new JobChecks(registry, processes, id -> Optional.ofNullable(TEMPLATES.get(id)),
            name -> Optional.ofNullable(name.equals("Ticket") ? TICKET : null), Duration.ofSeconds(30), 10).check();
    }

    private static JobDefinition<In> perItem(String name, JobItems items, ProcessDefinition<In, In, ProcessContext> p) {
        return JobDefinition.forEach(name, "0 * * * * *", ZoneOffset.UTC, items, p, (at, row) -> new In(at));
    }

    @Test
    void wellDeclaredPerItemJobsPass() {
        assertThat(check(perItem("expire", JobItems.template("it.job.due", at -> Map.of("now", at), "item_id"),
            ITEM))).isEmpty();
        // Column names compare as the template's do, ignoring case.
        assertThat(check(perItem("expire", JobItems.template("it.job.due", at -> Map.of("now", at), "ITEM_ID"),
            ITEM).parallelism(5))).isEmpty();
    }

    @Test
    void everyProblemOfAPerItemJobIsReported() {
        List<CheckProblem> problems = check(
            perItem("missing", JobItems.template("it.job.none", "item_id"), ITEM),
            perItem("open", JobItems.template("it.job.due", at -> Map.of("now", at, "extra", 1), "other"),
                NOT_INTERNAL).itemLockWait(Duration.ofMinutes(1)).parallelism(6),
            perItem("unsorted", JobItems.template("it.job.unsorted", "item_id"), ITEM),
            perItem("noparams", JobItems.template("it.job.due", "item_id"), ITEM),
            perItem("throws", JobItems.template("it.job.due", at -> {
                throw new IllegalStateException("no");
            }, "item_id"), ITEM));

        assertThat(problems).extracting(CheckProblem::format).containsExactly(
            "JOB | job missing | selects with SQL template it.job.none, which does not exist",
            "JOB | job open | runs item process ITEM_OPEN, which is not internal(): item processes take their input"
                + " from the selection (decision D41)",
            "JOB | job open | itemLockWait PT1M is longer than jabiz.process.lock-timeout PT30S",
            "WARNING JOB | job open | parallelism 6 is more than half the connection pool (10): requests may wait for"
                + " connections",
            "JOB | job open | selection template it.job.due has no result column other for the item key",
            "JOB | job open | selection template it.job.due has no parameter extra",
            "JOB | job unsorted | selection template it.job.unsorted is public; a job's selection is not",
            "JOB | job unsorted | selection template it.job.unsorted declares no list.defaultSort",
            "JOB | job unsorted | selection template it.job.unsorted declares no list.key",
            "JOB | job noparams | selection template it.job.due needs parameter now, which the job does not give",
            "JOB | job throws | the parameters of selection template it.job.due could not be computed:"
                + " java.lang.IllegalStateException: no");
        assertThat(problems.get(3).severity()).isEqualTo(CheckProblem.Severity.WARNING);
    }

    @Test
    void theSelectionReadsMaskedFieldsMasked() {
        assertThat(check(perItem("masked", JobItems.template("it.job.masked", "item_id"), ITEM),
            perItem("filtered", JobItems.template("it.job.filtered", "item_id"), ITEM)))
            .extracting(CheckProblem::format).containsExactly(
                "JOB | job masked | selection template it.job.masked takes the item key from masked field"
                    + " Ticket.taxId; the selection reads it masked",
                "WARNING JOB | job masked | selection template it.job.masked names masked field Ticket.taxId, which"
                    + " the selection reads masked",
                "WARNING JOB | job filtered | selection template it.job.filtered names masked field Ticket.taxId,"
                    + " which the selection reads masked");
    }

    @Test
    void wellDeclaredJobsPass() {
        assertThat(check(JobDefinition.cron("tick", "0 5 0 1 * *", ZoneOffset.UTC, REGISTERED, In::new))).isEmpty();
    }

    @Test
    void everyProblemIsReported() {
        List<CheckProblem> problems = check(
            JobDefinition.cron("tick", "0 5 0 1 * *", ZoneOffset.UTC, REGISTERED, In::new),
            JobDefinition.cron("tick", "every day", ZoneOffset.UTC, REGISTERED, In::new),
            JobDefinition.cron("other", "0 0 * * * *", ZoneOffset.UTC, UNREGISTERED, In::new));

        assertThat(problems).extracting(CheckProblem::format).containsExactly(
            "JOB | job tick | is declared more than once",
            "JOB | job tick | cron expression 'every day' is not valid (six fields: second minute hour day month "
                + "weekday)",
            "JOB | job other | runs process OTHER v1, which is not registered as a bean");
    }

    @Test
    void nextTimesFollowTheCronInItsZone() {
        JobDefinition<In> monthly = JobDefinition.cron("close", "0 5 0 1 * *", ZoneId.of("Asia/Tokyo"), REGISTERED,
            In::new);

        assertThat(JobScheduler.nextTime(monthly, Instant.parse("2026-01-31T09:00:00Z")))
            .isEqualTo(Instant.parse("2026-01-31T15:05:00Z"));
        assertThat(JobRunner.idempotencyKey("close", Instant.parse("2026-01-31T15:05:00Z")))
            .isEqualTo("job:close:2026-01-31T15:05:00Z");
    }
}
