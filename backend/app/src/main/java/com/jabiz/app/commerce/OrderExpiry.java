package com.jabiz.app.commerce;

import com.jabiz.context.RequestContext;
import com.jabiz.job.JobDefinition;
import com.jabiz.job.JobItems;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.PermissionDeniedException;
import com.jabiz.runtime.process.steps.CallProcess;
import com.jabiz.runtime.process.steps.HoldLock;
import com.jabiz.runtime.process.steps.LoadEntity;
import com.jabiz.runtime.storage.Rows;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static com.jabiz.app.commerce.CommerceEntities.ORDER_DATASET;
import static com.jabiz.app.commerce.CommerceEntities.PLACED;

/**
 * Unpaid orders expire (ROADMAP phase 16k; decision D42), the sample of a per-item job: every day at 03:10 Tokyo time
 * the job {@value #JOB} selects the placed orders still unpaid {@code app.commerce.unpaid-order-ttl} (14 days by
 * default) after they were placed, with the template {@value #TEMPLATE}, and runs {@value #EXPIRE} for each in a
 * transaction of its own: an order that cannot be cancelled fails alone, and an order a request is working on is
 * left to the next day (busy) instead of holding the others up.
 *
 * <p>The selection only proposes: {@value #EXPIRE} takes the order's lock ({@link #lockName}), reads the order again
 * and cancels it ({@code ORDER_CANCEL}, as a sub-process) only if it is still placed, unpaid and past its time. The payment of an
 * order ({@code ORDER_RECORD_PAYMENT}) takes the same lock, so a payment arriving just then and the expiry do not
 * interleave: whichever comes second sees what the first did. {@value #EXPIRE} is {@code internal()}: its input comes
 * from the selection, not from a person.
 */
@Configuration
class OrderExpiry {

    static final String JOB = "commerce.unpaid-order-expire";
    static final String TEMPLATE = "commerce.job.unpaid-orders";
    static final String EXPIRE = "ORDER_EXPIRE";
    static final Duration DEFAULT_TTL = Duration.ofDays(14);

    /** @param orderId the order the selection found */
    record ExpireInput(@NotNull UUID orderId) {}

    /** @param expired whether the order was cancelled; false when it had been paid or changed meanwhile */
    record ExpireOutput(String orderId, boolean expired) {}

    private static final String ORDER_ID = "orderId";
    private static final String ORDER = "order";
    private static final String CANCEL = "cancel";

    /** The lock of one order, taken by the processes that must not interleave on it. */
    static String lockName(Object orderId) {
        return "commerce.order:" + orderId;
    }

    /**
     * The item process: cancels the order if, under its lock, it is still placed, unpaid and older than {@code ttl}
     * (the selection only proposes it).
     */
    static ProcessDefinition<ExpireInput, ExpireOutput, ProcessContext> expireProcess(Duration ttl) {
        return ProcessDefinition.define(EXPIRE, 1, ExpireInput.class, ExpireOutput.class, ProcessContext.class, pb -> pb
            .description("Cancels an order still unpaid after its time to pay.")
            .permissions("commerce.order.cancel")
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(ORDER_ID, input.orderId().toString());
                return ctx;
            })
            .outputMapper(ctx -> new ExpireOutput((String) ctx.get(ORDER_ID), ctx.contains(CANCEL)))
            // internal() keeps it out of the catalog only, until D41 (16l) refuses anyone but the system itself.
            .compute("Refuse other callers", (metadata, ctx) -> requireSystem(ctx))
            // Before reading: a payment being recorded right now finishes first, and is then seen below.
            .step("Lock the order", HoldLock.<ProcessContext>exclusive(ctx -> lockName(ctx.get(ORDER_ID))))
            .step("Read the order again", LoadEntity.by(ORDER_DATASET, ORDER_ID, ORDER))
            .compute("Still unpaid?", (metadata, ctx) -> {
                EntityInstance order = ctx.get(ORDER, EntityInstance.class);
                Instant ordered = order.get("orderedTime");
                if (PLACED.equals(order.get("status")) && order.get("paidTime") == null
                    && ordered.isBefore(ctx.opTime().minus(ttl))) {
                    ctx.put(CANCEL, new CommerceProcesses.CancelInput(String.valueOf(order.id())));
                }
            })
            .step("Cancel it", CallProcess.<ProcessContext>when(ctx -> ctx.contains(CANCEL),
                CommerceProcesses.ORDER_CANCEL, 1, ctx -> ctx.get(CANCEL), null)));
    }

    /** Only the job runs the expiry: not a person, whatever their permissions. */
    static void requireSystem(ProcessContext ctx) {
        if (!RequestContext.SYSTEM_ACTOR.equals(ctx.request().actorId())) {
            throw new PermissionDeniedException("commerce.order.cancel",
                "Orders expire by the job " + JOB + " only");
        }
    }

    /** @param ttl how long a placed order may stay unpaid ({@code app.commerce.unpaid-order-ttl}, 14 days) */
    @Bean
    ProcessDefinition<ExpireInput, ExpireOutput, ProcessContext> orderExpireProcess(
        @Value("${app.commerce.unpaid-order-ttl:14d}") Duration ttl) {
        return expireProcess(ttl);
    }

    @Bean
    JobDefinition<ExpireInput> unpaidOrderExpireJob(
        @Value("${app.commerce.unpaid-order-ttl:14d}") Duration ttl,
        ProcessDefinition<ExpireInput, ExpireOutput, ProcessContext> orderExpireProcess) {
        return JobDefinition.forEach(JOB, "0 10 3 * * *", ZoneId.of("Asia/Tokyo"),
            JobItems.template(TEMPLATE, scheduledTime -> Map.of("cutoff", scheduledTime.minus(ttl)), "orderId"),
            orderExpireProcess, (scheduledTime, row) -> new ExpireInput(Rows.uuid(row.get("orderId"))));
    }
}
