package com.jabiz.app.it.fixture;

import com.jabiz.approval.ApprovalSubject;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.approval.ApprovalCase;
import com.jabiz.runtime.approval.ApprovalOutcome;
import com.jabiz.runtime.approval.RequireApproval;
import com.jabiz.runtime.approval.WithdrawApproval;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * An approval subject and processes of the approval and SoD tests (docs/design/18-numbering-approvals-tasks.md
 * sections 3 and 4): a payment asks whether it needs approval; the amount is a fact and part of the content, the
 * memo only part of the content.
 */
public final class ItApprovalFixtures {

    public static final String SUBJECT = "it.payment";
    public static final String PREPARE = "it.approval.prepare";
    public static final String SOD_PREPARE = "it.sod.prepare";

    public record PayInput(@NotBlank String paymentId, @NotNull BigDecimal amount, @NotBlank String channel,
        String memo, Instant businessTime) {}

    public record PayOutput(String status, String requestId) {}

    public record NothingInput(String note) {}

    private static final String APPROVAL = "approval";

    public static final ProcessDefinition<PayInput, PayOutput, ProcessContext> PAY =
        ProcessDefinition.define("IT_PAY", 1, PayInput.class, PayOutput.class, ProcessContext.class, pb -> pb
            .permissions(PREPARE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> {
                ApprovalOutcome outcome = ctx.get(APPROVAL, ApprovalOutcome.class);
                return new PayOutput(outcome.status().name(), outcome.requestId());
            })
            .step("Ask for approval", RequireApproval.of(SUBJECT, ctx -> {
                PayInput input = ctx.get("input", PayInput.class);
                Map<String, Object> content = new HashMap<>();
                content.put("amount", input.amount());
                content.put("channel", input.channel());
                content.put("memo", input.memo());
                return new ApprovalCase(input.paymentId(), Map.of("amount", input.amount(), "channel",
                    input.channel()), content, input.businessTime(), null);
            }, APPROVAL)));

    public static final ProcessDefinition<PayInput, PayOutput, ProcessContext> CANCEL =
        ProcessDefinition.define("IT_PAY_CANCEL", 1, PayInput.class, PayOutput.class, ProcessContext.class, pb -> pb
            .permissions(PREPARE)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new PayOutput("WITHDRAWN", null))
            .step("Withdraw the approval", WithdrawApproval.of(SUBJECT,
                ctx -> ctx.get("input", PayInput.class).paymentId())));

    /** Needs {@link #SOD_PREPARE} and does nothing: the SoD check at the entry decides. */
    public static final ProcessDefinition<NothingInput, NothingInput, ProcessContext> SOD_GUARDED =
        ProcessDefinition.define("IT_SOD_PREPARE", 1, NothingInput.class, NothingInput.class, ProcessContext.class,
            pb -> pb
                .permissions(SOD_PREPARE)
                .contextFactory((start, input) -> new ProcessContext(start))
                .outputMapper(ctx -> new NothingInput("done"))
                .compute("Nothing", (metadata, ctx) -> { }));

    private ItApprovalFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        ApprovalSubject itPaymentSubject() {
            // Payments are no entity here; the tickets stand in for them in the audit trail (AuditRecordsIT).
            return ApprovalSubject.define(SUBJECT, s -> s.entity(ItFixtures.TICKET.name).number("amount")
                .text("channel"));
        }

        @Bean
        ProcessDefinition<PayInput, PayOutput, ProcessContext> itPay() {
            return PAY;
        }

        @Bean
        ProcessDefinition<PayInput, PayOutput, ProcessContext> itPayCancel() {
            return CANCEL;
        }

        @Bean
        ProcessDefinition<NothingInput, NothingInput, ProcessContext> itSodGuarded() {
            return SOD_GUARDED;
        }
    }
}
