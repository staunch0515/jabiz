package com.jabiz.app.it.fixture;

import com.jabiz.entity.Violation;
import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.runtime.numbering.AssignNumber;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Sequences and processes of the numbering tests (docs/design/18-numbering-approvals-tasks.md section 2): each
 * process draws a number and fails afterwards when asked to, so that a test can see the number given back.
 */
public final class ItNumberingFixtures {

    public static final String PERMISSION = "it.numbering";
    public static final String PLAIN = "it.plain";
    public static final String SCOPED = "it.scoped";

    public record DrawInput(String scope, boolean fail, boolean skip) {}

    public record DrawOutput(String number) {}

    private static final String NUMBER = "number";

    /** Draws the next number of {@link #SCOPED} in the input's scope, unless {@code skip}. */
    public static final ProcessDefinition<DrawInput, DrawOutput, ProcessContext> DRAW_SCOPED =
        ProcessDefinition.define("IT_DRAW_SCOPED", 1, DrawInput.class, DrawOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new DrawOutput(ctx.contains(NUMBER) ? ctx.get(NUMBER, String.class) : null))
            .step("Draw", AssignNumber.when(ctx -> !ctx.get("input", DrawInput.class).skip(), SCOPED,
                ctx -> ctx.get("input", DrawInput.class).scope(), NUMBER))
            .compute("Fail when asked", (metadata, ctx) -> failWhenAsked(ctx)));

    /** Draws the next number of the unscoped {@link #PLAIN}. */
    public static final ProcessDefinition<DrawInput, DrawOutput, ProcessContext> DRAW_PLAIN =
        ProcessDefinition.define("IT_DRAW_PLAIN", 1, DrawInput.class, DrawOutput.class, ProcessContext.class, pb -> pb
            .permissions(PERMISSION)
            .contextFactory((start, input) -> {
                ProcessContext ctx = new ProcessContext(start);
                ctx.put("input", input);
                return ctx;
            })
            .outputMapper(ctx -> new DrawOutput(ctx.get(NUMBER, String.class)))
            .step("Draw", AssignNumber.of(PLAIN, NUMBER))
            .compute("Fail when asked", (metadata, ctx) -> failWhenAsked(ctx)));

    private static void failWhenAsked(ProcessContext ctx) {
        if (ctx.get("input", DrawInput.class).fail()) {
            ctx.reject(new Violation(null, "IT_FAILED", "failed after drawing"));
        }
    }

    private ItNumberingFixtures() {}

    @Configuration
    static class Beans {

        @Bean
        NumberSequence itPlainSequence() {
            return NumberSequence.define(PLAIN, s -> s.format("P-{n}").startAt(100));
        }

        @Bean
        NumberSequence itScopedSequence() {
            return NumberSequence.define(SCOPED, s -> s.format("S-{scope}-{n:4}").scoped());
        }

        @Bean
        ProcessDefinition<DrawInput, DrawOutput, ProcessContext> itDrawScoped() {
            return DRAW_SCOPED;
        }

        @Bean
        ProcessDefinition<DrawInput, DrawOutput, ProcessContext> itDrawPlain() {
            return DRAW_PLAIN;
        }
    }
}
