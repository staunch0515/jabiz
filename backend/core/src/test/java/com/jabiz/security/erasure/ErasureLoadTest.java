package com.jabiz.security.erasure;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The three ways a load reads (ROADMAP 17zz22) and what the eraser gets of them. */
class ErasureLoadTest {

    @Test
    void rowsAsBeforeCountsAndTotalsWithConditions() {
        ErasureLoad rows = ErasureLoad.of("points", "urn:d", "userId");
        assertThat(rows).isEqualTo(new ErasureLoad("points", "urn:d", "userId"));
        assertThat(rows.kind()).isEqualTo(ErasureLoad.Kind.ROWS);
        assertThat(rows.field()).isNull();
        assertThat(rows.conditions()).isEmpty();

        ErasureLoad open = ErasureLoad.count("open", "urn:d", "userId").where("status", "OPEN", "HELD")
            .where("region", "EAST");
        assertThat(open.kind()).isEqualTo(ErasureLoad.Kind.COUNT);
        assertThat(open.conditions()).containsExactly(new ErasureLoad.Condition("status", List.of("OPEN", "HELD")),
            new ErasureLoad.Condition("region", List.of("EAST")));

        ErasureLoad total = ErasureLoad.sum("balance", "urn:d", "ownerId", "amount").where("status", "OPEN");
        assertThat(total.kind()).isEqualTo(ErasureLoad.Kind.SUM);
        assertThat(total.field()).isEqualTo("amount");
        // where() leaves the load it was called on as it was.
        ErasureLoad base = ErasureLoad.count("c", "urn:d", "userId");
        base.where("status", "OPEN");
        assertThat(base.conditions()).isEmpty();
    }

    @Test
    void theSameNameMeansTheSameOnlyWithTheSameKindFieldAndConditions() {
        assertThat(ErasureLoad.count("a", "urn:d", "u").where("s", "X"))
            .isEqualTo(ErasureLoad.count("a", "urn:d", "u").where("s", "X"))
            .isNotEqualTo(ErasureLoad.count("a", "urn:d", "u").where("s", "Y"))
            .isNotEqualTo(ErasureLoad.of("a", "urn:d", "u").where("s", "X"));
        assertThat(ErasureLoad.sum("a", "urn:d", "u", "x")).isNotEqualTo(ErasureLoad.sum("a", "urn:d", "u", "y"));
    }

    @Test
    void refusesWhatCouldNeverBeRead() {
        assertThatThrownBy(() -> ErasureLoad.of("1bad", "urn:d", "u")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ErasureLoad.of("a", " ", "u")).hasMessageContaining("names no dataset");
        assertThatThrownBy(() -> ErasureLoad.count("a", "urn:d", "")).hasMessageContaining("names no user field");
        assertThatThrownBy(() -> ErasureLoad.sum("a", "urn:d", "u", " ")).hasMessageContaining("sums no field");
        assertThatThrownBy(() -> new ErasureLoad("a", "urn:d", "u", ErasureLoad.Kind.COUNT, "x", List.of()))
            .hasMessageContaining("does not sum it");
        assertThatThrownBy(() -> new ErasureLoad("a", "urn:d", "u", null, null, List.of()))
            .isInstanceOf(NullPointerException.class);
        ErasureLoad load = ErasureLoad.count("a", "urn:d", "u");
        assertThatThrownBy(() -> load.where("status")).hasMessageContaining("gives no value");
        assertThatThrownBy(() -> load.where("status", (Object[]) null)).hasMessageContaining("gives no value");
        assertThatThrownBy(() -> load.where("status", "OPEN", null)).hasMessageContaining("gives a null value");
        assertThatThrownBy(() -> load.where(" ", "OPEN")).hasMessageContaining("names no field");
    }

    @Test
    void theSubjectGivesCountsAndTotalsByNameAndAMisspeltNameIsAnError() {
        ErasureSubject subject = new ErasureSubject("u1", Map.of("rows", List.of(Map.of("a", 1))),
            Map.of("open", 150L), Map.of("balance", new BigDecimal("1200")));
        assertThat(subject.rows("rows")).hasSize(1);
        assertThat(subject.rows("nothing")).isEmpty();
        assertThat(subject.count("open")).isEqualTo(150);
        assertThat(subject.sum("balance")).isEqualByComparingTo("1200");
        assertThatThrownBy(() -> subject.count("opne")).hasMessageContaining("No count load named opne");
        assertThatThrownBy(() -> subject.count("balance")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> subject.sum("open")).hasMessageContaining("No sum load named open");
        // A rows() left over after a load became a count or a sum is an error, not an empty list.
        assertThatThrownBy(() -> subject.rows("open")).hasMessageContaining("reads a count or a sum, not rows");
        assertThatThrownBy(() -> subject.rows("balance")).hasMessageContaining("reads a count or a sum, not rows");
        // The rows-only constructor of before 17zz22.
        ErasureSubject before = new ErasureSubject("u1", Map.of());
        assertThat(before.counts()).isEmpty();
        assertThat(before.sums()).isEmpty();
        assertThatThrownBy(() -> before.count("open")).isInstanceOf(IllegalArgumentException.class);
    }
}
