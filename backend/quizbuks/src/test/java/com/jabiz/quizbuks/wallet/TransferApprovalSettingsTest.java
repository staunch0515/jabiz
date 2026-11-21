package com.jabiz.quizbuks.wallet;

import com.jabiz.context.RequestContext;
import com.jabiz.param.ParamValues;
import com.jabiz.process.IdAssigner;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.approval.ApprovalEntities;
import com.jabiz.runtime.approval.ApprovalOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The transfers' approval consumer reads its settings as optional (review of F-LAUNCH Q3): an approved transfer or
 * withdrawal whose settings have no value in effect is not sent but rejected as {@code NOT_SENDABLE}, which the
 * process's later steps turn into finance's task and the taker's hold, as for any transfer that can no longer be sent.
 * (A setting in effect cannot be deleted, so this is what the decision sees when one was never set.)
 */
class TransferApprovalSettingsTest {

    private static final Instant AT = Instant.parse("2026-12-01T00:00:00Z");

    @Test
    void anApprovedTransferWithoutItsSettingsIsRejectedAsNotSendable() {
        for (String method : new String[] {"STRIPE", QbPayouts.BANK}) {
            ProcessContext ctx = approved(method, Map.of());
            TransferProcesses.decided(ctx);
            assertThat(ctx.contains(TransferProcesses.SEND)).as(method).isFalse();
            assertThat(ctx.get(TransferProcesses.STATUS_AFTER)).as(method).isEqualTo(TransferStatus.REJECTED);
            assertThat(ctx.get(TransferProcesses.REASON)).as(method).isEqualTo(PayoutChecks.NOT_SENDABLE);
            assertThat(pending(ctx)).as(method).containsEntry("status", TransferStatus.REJECTED)
                .containsEntry("failureCode", PayoutChecks.NOT_SENDABLE);
        }
    }

    @Test
    void withItsSettingsTheSameTransferIsSent() {
        ProcessContext ctx = approved("STRIPE", Map.of(QbParams.TRANSFER_ENABLED, true,
            QbParams.TRANSFER_HOLD_DAYS, BigDecimal.ZERO, QbParams.WITHDRAWAL_BANK_ENABLED, true));
        TransferProcesses.decided(ctx);
        assertThat(ctx.contains(TransferProcesses.SEND)).isTrue();
        assertThat(ctx.contains(TransferProcesses.REASON)).isFalse();
        ProcessContext bank = approved(QbPayouts.BANK, Map.of(QbParams.TRANSFER_HOLD_DAYS, BigDecimal.ZERO,
            QbParams.WITHDRAWAL_BANK_ENABLED, true));
        TransferProcesses.decided(bank);
        assertThat(bank.contains(TransferProcesses.SEND)).isTrue();
    }

    private static ProcessContext approved(String method, Map<String, Object> settings) {
        ProcessContext ctx = new ProcessContext(new ProcessStart(1L, AT, RequestContext.system(Locale.ENGLISH, "test"),
            IdAssigner.NONE));
        Map<String, Object> transfer = new LinkedHashMap<>();
        transfer.put("status", TransferStatus.REVIEW);
        transfer.put("approvalRequestId", "request-1");
        transfer.put("amount", new BigDecimal("1500"));
        transfer.put("method", method);
        transfer.put("externalAccountId", "acct_1");
        ctx.put(TransferProcesses.TRANSFER, List.of(new EntityInstance("transfer-1", QbPayouts.TRANSFER, 1, null,
            transfer)));
        ctx.put(TransferProcesses.ACCOUNT, List.of(new EntityInstance("account-1", QbPayouts.ACCOUNT, 1, null,
            Map.of("status", PayoutAccountStatus.ENABLED, "externalAccountId", "acct_1"))));
        ctx.put(TransferProcesses.BAN, List.of());
        ctx.put(TransferProcesses.LEDGER, List.of(Map.of("available", new BigDecimal("1500"), "held",
            BigDecimal.ZERO)));
        ctx.put(TransferProcesses.PARAMS, new ParamValues(AT, settings));
        ctx.put(TransferProcesses.INPUT, new TransferProcesses.ApprovalResultInput(QbPayouts.APPROVAL_SUBJECT,
            "transfer-1", ApprovalEntities.APPROVED, "request-1", "user-1", null, null));
        ctx.put(TransferProcesses.APPROVAL, new ApprovalOutcome(ApprovalOutcome.Status.APPROVED, "request-1"));
        return ctx;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pending(ProcessContext ctx) {
        return (Map<String, Object>) ctx.get(TransferProcesses.PENDING_WRITE);
    }
}
