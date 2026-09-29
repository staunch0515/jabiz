package com.jabiz.runtime.numbering;

import com.jabiz.numbering.NumberSequence;
import com.jabiz.process.ProcessContext;
import com.jabiz.runtime.check.CheckProblem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Startup checks of number sequences and of the steps that draw from them. */
class NumberingChecksTest {

    private static NumberSequenceRegistry registry(NumberSequence... sequences) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        for (int i = 0; i < sequences.length; i++) {
            beans.addBean("sequence" + i, sequences[i]);
        }
        return new NumberSequenceRegistry(beans.getBeanProvider(NumberSequence.class));
    }

    private static final NumberSequence JOURNAL =
        NumberSequence.define("fin.journal", s -> s.format("JE-{n:4}").scoped());
    private static final NumberSequence INVOICE = NumberSequence.define("fin.invoice", s -> s.format("INV-{n}"));

    @Test
    void aSequenceNameDeclaredTwiceIsReported() {
        NumberSequenceRegistry sequences = registry(JOURNAL, INVOICE,
            NumberSequence.define("fin.invoice", s -> s.format("X-{n}")));
        assertThat(new NumberingChecks(sequences).check()).extracting(CheckProblem::location, CheckProblem::message)
            .containsExactly(tuple("NumberSequence fin.invoice", "declared more than once"));
        assertThat(sequences.find("fin.invoice")).get().extracting(NumberSequence::format)
            .hasToString("INV-{n}");
        assertThat(new NumberingChecks(registry(JOURNAL, INVOICE)).check()).isEmpty();
    }

    @Test
    void aStepMustNameADeclaredSequenceWithTheRightScoping() {
        AssignNumber<ProcessContext> step = new AssignNumber<>(registry(JOURNAL, INVOICE), null, null, "default");
        Function<ProcessContext, String> year = ctx -> "2026";

        assertThat(step.problems(AssignNumber.<ProcessContext>of("fin.journal", year, "no").metadata())).isEmpty();
        assertThat(step.problems(AssignNumber.<ProcessContext>of("fin.invoice", "no").metadata())).isEmpty();
        assertThat(step.problems(AssignNumber.<ProcessContext>of("fin.nothing", "no").metadata()))
            .containsExactly("number sequence fin.nothing is not declared");
        assertThat(step.problems(AssignNumber.<ProcessContext>of("fin.journal", "no").metadata()))
            .containsExactly("number sequence fin.journal is scoped: give the scope");
        assertThat(step.problems(AssignNumber.<ProcessContext>of("fin.invoice", year, "no").metadata()))
            .containsExactly("number sequence fin.invoice is not scoped: give no scope");
        assertThat(step.problems(AssignNumber.<ProcessContext>when(ctx -> true, "fin.journal", null, "no")
            .metadata())).containsExactly("number sequence fin.journal is scoped: give the scope");
    }
}
