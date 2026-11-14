package com.jabiz.quizbuks.fee;

import com.jabiz.context.RequestContext;
import com.jabiz.process.IdAssigner;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessStart;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * F-M15c review L-b: a credit note's give-back reads one more related note and one more payment part than it uses, and
 * refuses when there are more than it reads (it never gives back on a part of them).
 */
class CreditNoteGiveBackTest {

    @Test
    void moreRelatedNotesThanAreReadAreRefused() {
        ProcessContext ctx = context();
        ctx.put(CreditNoteGiveBack.RELATED, rows(QbCreditNotes.CREDIT_NOTE, FeeSettlement.MAX_PARTS + 1));
        ctx.put(CreditNoteGiveBack.PARTS, List.of());
        assertThatThrownBy(() -> CreditNoteGiveBack.plan(ctx)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("More credit notes on the fee");
    }

    @Test
    void morePartsThanAreReadAreRefused() {
        ProcessContext ctx = context();
        ctx.put(CreditNoteGiveBack.RELATED, List.of());
        ctx.put(CreditNoteGiveBack.PARTS, rows(QbFeeAccount.PAYMENT, FeeSettlement.MAX_PARTS + 1));
        assertThatThrownBy(() -> CreditNoteGiveBack.plan(ctx)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("More payment parts");
    }

    private static ProcessContext context() {
        ProcessContext ctx = new ProcessContext(new ProcessStart(1L, Instant.parse("2026-12-01T00:00:00Z"),
            RequestContext.system(Locale.ENGLISH, "test"), IdAssigner.NONE));
        ctx.put(CreditNoteGiveBack.INPUT, new CreditNoteGiveBack.GiveBackInput("s", "note-1", BigDecimal.TEN));
        return ctx;
    }

    private static List<EntityInstance> rows(String entity, int count) {
        List<EntityInstance> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new EntityInstance("id-" + i, entity, 1, null, Map.of()));
        }
        return rows;
    }
}
