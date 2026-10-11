package com.jabiz.runtime.inbound;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.security.Sensitive;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;

/**
 * Receiving an inbound webhook (decision D37 item 2): {@value #RECEIVE} stores one verified event and hands it to the
 * application through the outbox, in one transaction, as an operation like every other platform write. Its caller is
 * the platform's receiver, which verified the signature and named the event before, and runs it as the system; the
 * application's processes run later, as consumers of {@code jabiz.inbound.<provider>.<type>}, never in the request.
 */
@Configuration
public class InboundProcesses {

    public static final String RECEIVE = "INBOUND_RECEIVE";
    private static final String INPUT = "input";
    private static final String STORED = "stored";

    /**
     * One verified event. The body is the request's, as received: {@link Sensitive}, so the operation record keeps
     * {@code ***} in its place, and so does {@link #toString()}.
     *
     * @param signedTime  when the provider signed the request
     * @param contentType the request's {@code Content-Type}, at most 100 characters
     * @param rawBody     the body as text (it was strict UTF-8)
     */
    public record ReceiveInput(@NotBlank String provider, @NotBlank String externalEventId, @NotBlank String eventType,
        Boolean livemode, @NotNull Instant signedTime, String contentType, @Sensitive @NotNull String rawBody) {

        @Override
        public String toString() {
            return "ReceiveInput[provider=" + provider + ", externalEventId=" + externalEventId + ", eventType="
                + eventType + ", livemode=" + livemode + ", signedTime=" + signedTime + ", contentType=" + contentType
                + ", rawBody=***]";
        }
    }

    /** @param inboundEventId the id of the stored event */
    public record ReceiveOutput(String inboundEventId) {}

    static final ProcessDefinition<ReceiveInput, ReceiveOutput, ProcessContext> RECEIVE_PROCESS =
        ProcessDefinition.define(RECEIVE, 1, ReceiveInput.class, ReceiveOutput.class, ProcessContext.class, pb -> pb
            .description("Stores a verified inbound webhook event and publishes it to the application.")
            .permissions(InboundPermissions.RECEIVE)
            .internal()
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put(INPUT, input);
                return ctx;
            })
            .outputMapper(ctx -> new ReceiveOutput(String.valueOf(ctx.get(STORED))))
            .step("Record and publish the event", RecordInboundEvent.of(INPUT, STORED)));

    @Bean
    ProcessDefinition<ReceiveInput, ReceiveOutput, ProcessContext> inboundReceiveProcess() {
        return RECEIVE_PROCESS;
    }
}
