package com.jabiz.app.it.fixture;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.entity.BaseEntityDefinitions;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.Violation;
import com.jabiz.event.EntityChangeEvents;
import com.jabiz.event.EventSubscription;
import com.jabiz.job.JobDefinition;
import com.jabiz.process.BlockingStep;
import com.jabiz.process.NoMetadata;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.process.steps.PublishEvent;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fixtures of the outbox and job tests (ROADMAP phase 9): ItMemo publishes its changes; IT_NOTE_CREATE publishes an
 * event of its own; two consumers log what they receive into ItEventLog; IT_TICK is run by the job {@code it.tick}.
 * Static probes count how often the consumers and the job's process really ran.
 */
public final class ItEventFixtures extends BaseEntityDefinitions {

    public static final String NOTE_CREATED = "it.note-created";
    public static final String NOTE_CONSUMER = "it.note-consumer";
    public static final String CHANGE_CONSUMER = "it.change-consumer";
    public static final String TICK_JOB = "it.tick";
    public static final String FAILING_JOB = "it.failing";
    static final String PERMISSION = "it.events";

    /** Runs of the note consumer's process per event id, including those rolled back. */
    public static final Map<String, AtomicInteger> CONSUMER_RUNS = new ConcurrentHashMap<>();
    /** Failures the note consumer still has to produce before it succeeds. */
    public static final AtomicInteger CONSUMER_FAILURES_LEFT = new AtomicInteger();
    /** Runs of IT_TICK, including those rolled back. */
    public static final AtomicInteger TICK_RUNS = new AtomicInteger();

    public static final EntityDefinition NOTE = EntityDefinition.define("ItMemo", eb -> {
        eb.physicalTable("it_memo");
        eb.primaryKey("memoId");
        eb.field("memoId", semanticIdentity("f_id", "urn:jabiz:entity:it:memo"));
        eb.field("text", f -> f.physicalColumn("f_text").required(true).asText(1000));
        eb.field("rowVersion", rowVersion("f_version"));
        eb.publishChanges();
    });

    public static final EntityDefinition EVENT_LOG = EntityDefinition.define("ItEventLog", eb -> {
        eb.physicalTable("it_event_log");
        eb.primaryKey("logId");
        eb.field("logId", semanticIdentity("f_id", "urn:jabiz:entity:it:event-log"));
        eb.field("source", f -> f.physicalColumn("f_source").required(true).asText(200));
        eb.field("consumer", f -> f.physicalColumn("f_consumer").required(true).asText(100));
        eb.field("rowVersion", rowVersion("f_version"));
    });

    public record NoteInput(@NotBlank String noteId, @NotBlank String text, boolean fail) {}

    public record NoteOutput(String noteId) {}

    public record LogInput(String logId, String source, String consumer) {}

    public record TickInput(Instant scheduledTime, boolean fail) {}

    /** Inserts a note and publishes {@value #NOTE_CREATED}; with {@code fail} it is rejected after publishing. */
    public static final ProcessDefinition<NoteInput, NoteOutput, ProcessContext> NOTE_CREATE =
        ProcessDefinition.define("IT_NOTE_CREATE", 1, NoteInput.class, NoteOutput.class, ProcessContext.class,
            pb -> pb
                .permissions(PERMISSION)
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put("input", input);
                    return ctx;
                })
                .outputMapper(ctx -> new NoteOutput(ctx.get("input", NoteInput.class).noteId()))
                .compute("Register the note", (metadata, ctx) -> {
                    NoteInput input = ctx.get("input", NoteInput.class);
                    ctx.changes().insert("ItMemo", Map.of("memoId", input.noteId(), "text", input.text()));
                })
                .step("Publish", PublishEvent.<ProcessContext>of(NOTE_CREATED, ctx -> {
                    NoteInput input = ctx.get("input", NoteInput.class);
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("memoId", input.noteId());
                    payload.put("text", input.text());
                    payload.put("password", "never-stored");
                    return payload;
                }))
                .compute("Maybe fail", (metadata, ctx) -> {
                    if (ctx.get("input", NoteInput.class).fail()) {
                        ctx.reject(new Violation(null, "IT_REJECTED", "rejected on purpose"));
                    }
                }));

    /** Logs one received event; fails while {@link #CONSUMER_FAILURES_LEFT} is positive. */
    public static final ProcessDefinition<LogInput, LogInput, ProcessContext> LOG_EVENT =
        ProcessDefinition.define("IT_LOG_EVENT", 1, LogInput.class, LogInput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get("input", LogInput.class))
            .compute("Log", (metadata, ctx) -> {
                LogInput input = ctx.get("input", LogInput.class);
                ctx.changes().insert("ItEventLog", Map.of("logId", input.logId(), "source", input.source(),
                    "consumer", input.consumer()));
            })
            .step("Count", Probe.class, NoMetadata.INSTANCE));

    /** Logs the tick of its scheduled time after a pause, so that runs of two instances overlap. */
    public static final ProcessDefinition<TickInput, TickInput, ProcessContext> TICK =
        ProcessDefinition.define("IT_TICK", 1, TickInput.class, TickInput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> ctx.get("input", TickInput.class))
            .step("Work a while", Pause.class, NoMetadata.INSTANCE)
            .compute("Log", (metadata, ctx) -> {
                TickInput input = ctx.get("input", TickInput.class);
                if (input.fail()) {
                    ctx.reject(new Violation(null, "IT_REJECTED", "tick failed on purpose"));
                    return;
                }
                ctx.changes().insert("ItEventLog", Map.of("logId", "tick:" + input.scheduledTime(),
                    "source", "job", "consumer", TICK_JOB));
            }));

    /** Counts runs of the consumer and fails while asked to. */
    @Component
    public static class Probe implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) {
            LogInput input = ctx.get("input", LogInput.class);
            CONSUMER_RUNS.computeIfAbsent(input.source(), key -> new AtomicInteger()).incrementAndGet();
            if (NOTE_CONSUMER.equals(input.consumer()) && CONSUMER_FAILURES_LEFT.getAndDecrement() > 0) {
                throw new IllegalStateException("consumer unavailable");
            }
        }
    }

    /** Counts runs of IT_TICK and takes its time (a blocking step may sleep). */
    @Component
    public static class Pause implements BlockingStep<NoMetadata, ProcessContext> {
        @Override
        public void run(NoMetadata metadata, ProcessContext ctx) throws InterruptedException {
            TICK_RUNS.incrementAndGet();
            Thread.sleep(300);
        }
    }

    private ItEventFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        EntityDefinition itMemoEntity() {
            return NOTE;
        }

        @Bean
        EntityDefinition itEventLogEntity() {
            return EVENT_LOG;
        }

        @Bean
        DatasetDefinition itMemoDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define("urn:jabiz:dataset:it:ItMemo", d -> d.targetEntityType("ItMemo")
                .asDefault().permissions("it.read", "it.write").storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        DatasetDefinition itEventLogDataset(@Value("${jabiz.storage.default-pool-ref:default}") String pool) {
            return DatasetDefinition.define("urn:jabiz:dataset:it:ItEventLog", d -> d
                .targetEntityType("ItEventLog").asDefault().permissions("it.read", "it.write")
                .storage(s -> s.connectionPoolRef(pool)));
        }

        @Bean
        ProcessDefinition<NoteInput, NoteOutput, ProcessContext> itMemoCreate() {
            return NOTE_CREATE;
        }

        @Bean
        ProcessDefinition<LogInput, LogInput, ProcessContext> itLogEvent() {
            return LOG_EVENT;
        }

        @Bean
        ProcessDefinition<TickInput, TickInput, ProcessContext> itTick() {
            return TICK;
        }

        @Bean
        EventSubscription<LogInput> itMemoConsumer() {
            return EventSubscription.of(NOTE_CONSUMER, NOTE_CREATED, LOG_EVENT,
                event -> new LogInput(NOTE_CONSUMER + ":" + event.eventId(), event.eventId().toString(),
                    NOTE_CONSUMER));
        }

        @Bean
        EventSubscription<LogInput> itChangeConsumer() {
            return EventSubscription.of(CHANGE_CONSUMER, EntityChangeEvents.eventType("ItMemo"), LOG_EVENT,
                event -> new LogInput(CHANGE_CONSUMER + ":" + event.eventId(), event.eventId().toString(),
                    CHANGE_CONSUMER));
        }

        @Bean
        JobDefinition<TickInput> itTickJob() {
            return JobDefinition.cron(TICK_JOB, "0 0 * * * *", ZoneOffset.UTC, TICK, at -> new TickInput(at, false));
        }

        @Bean
        JobDefinition<TickInput> itFailingJob() {
            return JobDefinition.cron(FAILING_JOB, "0 30 * * * *", ZoneOffset.UTC, TICK,
                at -> new TickInput(at, true));
        }
    }
}
