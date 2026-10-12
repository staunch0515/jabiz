package com.jabiz.quizbuks.wallet;

import com.jabiz.event.DomainEvent;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.runtime.inbound.InboundEvents;
import com.jabiz.runtime.inbound.LoadInboundEvent;
import com.jabiz.runtime.inbound.ReceivedEvent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The consumers of Stripe's checkout events (docs/quizbuks/plans/Q6-payments.md, D-Q6-3, D-Q6-4), run by the platform
 * as the system for each verified, stored and deduplicated event ({@code internal()}, platform decision D41):
 * <ul>
 *   <li>{@value #CONFIRM} ({@code checkout.session.completed}): credits the top-up once ({@link TopUpCrediting});</li>
 *   <li>{@value #EXPIRE} ({@code checkout.session.expired}): an opening or open top-up expires; a paid one becomes a
 *       task (it should not happen).</li>
 * </ul>
 * The top-up is found by the session's {@code client_reference_id}; the session id, both metadata references, the
 * mode, the currency and the amount must all agree with the record ({@link TopUpChecks}).
 */
public final class TopUpEvents {

    public static final String CONFIRM = "QB_TOPUP_CONFIRM";
    public static final String EXPIRE = "QB_TOPUP_EXPIRE";
    public static final String CONFIRM_CONSUMER = "qb.topup-confirm";
    public static final String EXPIRE_CONSUMER = "qb.topup-expire";

    /** The input of the consumers of Stripe's events: the stored event, which the process loads itself. */
    public record InboundRef(@NotBlank @Size(max = 64) String inboundEventId) {}

    static final String INPUT = "input";
    static final String EVENT = "event";

    public static ProcessDefinition<InboundRef, TopUpCrediting.Outcome, ProcessContext> confirm(
        StripeSettings settings) {
        return checkoutConsumer(CONFIRM, StripeService.CHECKOUT_COMPLETED, TopUpCrediting.Mode.PAID,
            "Credits a sponsor's top-up once Stripe reports its checkout paid.", settings);
    }

    public static ProcessDefinition<InboundRef, TopUpCrediting.Outcome, ProcessContext> expire(
        StripeSettings settings) {
        return checkoutConsumer(EXPIRE, StripeService.CHECKOUT_EXPIRED, TopUpCrediting.Mode.EXPIRED,
            "Marks a sponsor's top-up expired once Stripe reports its checkout expired.", settings);
    }

    private static ProcessDefinition<InboundRef, TopUpCrediting.Outcome, ProcessContext> checkoutConsumer(
        String name, String eventType, TopUpCrediting.Mode mode, String description, StripeSettings settings) {
        return ProcessDefinition.define(name, 1, InboundRef.class, TopUpCrediting.Outcome.class, ProcessContext.class,
            pb -> pb
                .description(description)
                .permissions(QbPermissions.WALLET_SYSTEM)
                .internal()
                .contextFactory((start, input) -> {
                    ProcessContext ctx = new ProcessContext(start);
                    ctx.put(INPUT, input);
                    return ctx;
                })
                .outputMapper(TopUpCrediting::outcome)
                .step("Load the event", LoadInboundEvent.of(StripeService.PROVIDER, eventType,
                    ctx -> ctx.get(INPUT, InboundRef.class).inboundEventId(), EVENT))
                .compute("Read the checkout", (metadata, ctx) -> ctx.put(TopUpCrediting.REPORT, report(ctx, mode)))
                .steps(b -> TopUpCrediting.lockAndLoad(b,
                    ctx -> TopUpCrediting.report(ctx).session().clientReferenceId()))
                .steps(b -> TopUpCrediting.apply(b, settings)));
    }

    static TopUpCrediting.Report report(ProcessContext ctx, TopUpCrediting.Mode mode) {
        ReceivedEvent event = ctx.get(EVENT, ReceivedEvent.class);
        return new TopUpCrediting.Report(mode, StripeObjects.session(event.at("data.object")), event.livemode(),
            happened(event), event.inboundEventId(), TopUpTasks.eventSource(event.externalEventId()),
            event.inboundEventId());
    }

    /**
     * When the event happened: its {@code created} (Stripe's, inside the signed body), not when this delivery was
     * signed (a delivery retried for days is signed days later). A {@code created} after the signature is not
     * believed.
     */
    static Instant happened(ReceivedEvent event) {
        Instant signed = event.signedTime() != null ? event.signedTime() : event.receivedTime();
        BigDecimal created = StripeObjects.decimal(event.at("created"));
        if (created == null || created.signum() <= 0 || created.stripTrailingZeros().scale() > 0
            || created.compareTo(BigDecimal.valueOf(Instant.MAX.getEpochSecond())) > 0) {
            return signed;
        }
        Instant time = Instant.ofEpochSecond(created.longValueExact());
        return signed != null && time.isAfter(signed) ? signed : time;
    }

    /** The input of a consumer from the outbox event's reference. */
    static InboundRef input(DomainEvent event) {
        return new InboundRef(InboundEvents.ref(event).inboundEventId());
    }

    private TopUpEvents() {}
}
