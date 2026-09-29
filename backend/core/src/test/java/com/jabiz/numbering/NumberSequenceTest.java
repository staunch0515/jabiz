package com.jabiz.numbering;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NumberSequenceTest {

    @Test
    void formatsWithPaddingScopeAndLiterals() {
        assertThat(NumberFormat.parse("JE-{n:4}").format(1, null)).isEqualTo("JE-0001");
        assertThat(NumberFormat.parse("INV-{n}").format(1004, null)).isEqualTo("INV-1004");
        assertThat(NumberFormat.parse("SO-{scope}-{n:6}").format(42, "2026")).isEqualTo("SO-2026-000042");
        assertThat(NumberFormat.parse("{n:2}/{scope}").format(7, "2026/MAN")).isEqualTo("07/2026/MAN");
        assertThat(NumberFormat.parse("{n}").format(3, null)).isEqualTo("3");
    }

    @Test
    void aNumberLongerThanItsPaddingIsWrittenInFull() {
        assertThat(NumberFormat.parse("JE-{n:4}").format(123456, null)).isEqualTo("JE-123456");
    }

    @Test
    void reportsEveryProblemOfAFormat() {
        assertThatThrownBy(() -> NumberFormat.parse("JE {x} {year}"))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("literal 'JE ' may only contain")
            .hasMessageContaining("unknown placeholder {x}")
            .hasMessageContaining("unknown placeholder {year}")
            .hasMessageContaining("needs exactly one {n} or {n:width}, has 0");
        assertThatThrownBy(() -> NumberFormat.parse("{n}-{n:3}")).hasMessageContaining("has 2");
        assertThatThrownBy(() -> NumberFormat.parse("{n:0}")).hasMessageContaining("unknown placeholder {n:0}");
        assertThatThrownBy(() -> NumberFormat.parse(" ")).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> NumberFormat.parse("A-{n}").format(0, null)).hasMessageContaining("start at 1");
    }

    @Test
    void declaresScopedAndUnscopedSequences() {
        NumberSequence journal = NumberSequence.define("fin.journal", s -> s.format("JE-{n:4}").scoped());
        assertThat(journal.scoped()).isTrue();
        assertThat(journal.startAt()).isEqualTo(1);
        assertThat(journal.scopeKey("2026/MAN")).isEqualTo("2026/MAN");
        assertThat(journal.format(12, "2026/MAN")).isEqualTo("JE-0012");

        NumberSequence invoice = NumberSequence.define("fin.invoice", s -> s.format("INV-{n}").startAt(1004));
        assertThat(invoice.scopeKey(null)).isEqualTo(NumberSequence.NO_SCOPE);
        assertThat(invoice.scopeKey("")).isEqualTo(NumberSequence.NO_SCOPE);
        assertThat(invoice.startAt()).isEqualTo(1004);
    }

    @Test
    void refusesWrongDeclarationsAndScopes() {
        assertThatThrownBy(() -> NumberSequence.define("Fin Journal", s -> s.format("{n}")))
            .hasMessageContaining("must be 1-100 lowercase letters");
        assertThatThrownBy(() -> NumberSequence.define("a", s -> {})).hasMessageContaining("needs a format");
        assertThatThrownBy(() -> NumberSequence.define("a", s -> s.format("{n}").startAt(0)))
            .hasMessageContaining("startAt must be at least 1");
        assertThatThrownBy(() -> NumberSequence.define("a", s -> s.format("{scope}-{n}")))
            .hasMessageContaining("uses {scope} but the sequence is not scoped");

        NumberSequence scoped = NumberSequence.define("a", s -> s.format("{n}").scoped());
        assertThatThrownBy(() -> scoped.scopeKey(null)).hasMessageContaining("needs a scope");
        assertThatThrownBy(() -> scoped.scopeKey("2026 MAN")).hasMessageContaining("needs a scope");
        NumberSequence unscoped = NumberSequence.define("b", s -> s.format("{n}"));
        assertThatThrownBy(() -> unscoped.scopeKey("2026")).hasMessageContaining("is not scoped");
    }

    @Property
    void paddedNumbersSortAsTheirValuesWithinTheWidth(@ForAll @LongRange(min = 1, max = 999_999) long a,
        @ForAll @LongRange(min = 1, max = 999_999) long b, @ForAll @IntRange(min = 6, max = 12) int width) {
        NumberFormat format = NumberFormat.parse("X-{n:" + width + "}");
        assertThat(Integer.signum(format.format(a, null).compareTo(format.format(b, null))))
            .isEqualTo(Long.signum(a - b));
    }
}
