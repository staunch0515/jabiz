package com.jabiz.process;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProcessContextTest {

    private static final Instant OP_TIME = Instant.parse("2026-03-01T09:00:00Z");
    private static final RequestContext REQUEST = RequestContext.system(Locale.JAPANESE, "req-1");

    private static ProcessContext context() {
        return new ProcessContext(new ProcessStart(12L, OP_TIME, REQUEST, IdAssigner.NONE));
    }

    @Test
    void exposesWhatTheExecutionStartedWith() {
        ProcessContext ctx = context();

        assertThat(ctx.processSeqId()).isEqualTo(12L);
        assertThat(ctx.opTime()).isEqualTo(OP_TIME);
        assertThat(ctx.request()).isSameAs(REQUEST);
        assertThat(ctx.changes().isEmpty()).isTrue();
        assertThat(ctx.hasViolations()).isFalse();
    }

    @Test
    void collectsViolations() {
        ProcessContext ctx = context();
        ctx.reject(new Violation("a", "RULE_A", "a broken"));
        ctx.violations().add(new Violation(null, "RULE_B", "b broken"));

        assertThat(ctx.hasViolations()).isTrue();
        assertThat(ctx.violations()).extracting(Violation::ruleCode).containsExactly("RULE_A", "RULE_B");
        assertThatThrownBy(() -> ctx.reject(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void storesTypedValues() {
        ProcessContext ctx = context();
        ctx.put("n", 5);
        assertThat(ctx.get("n", Integer.class)).isEqualTo(5);
        assertThat(ctx.contains("n")).isTrue();
        assertThatThrownBy(() -> ctx.get("n", String.class)).isInstanceOf(IllegalStateException.class);
        ctx.put("n", null);
        assertThat(ctx.get("n")).isNull();
        assertThat(ctx.get("n", String.class)).isNull();
        assertThatThrownBy(() -> ctx.put(null, 1)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void startNeedsTimeRequestAndIds() {
        assertThatThrownBy(() -> new ProcessStart(1L, null, REQUEST, IdAssigner.NONE))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProcessStart(1L, OP_TIME, null, IdAssigner.NONE))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProcessStart(1L, OP_TIME, REQUEST, null))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProcessContext(null)).isInstanceOf(NullPointerException.class);
    }
}
