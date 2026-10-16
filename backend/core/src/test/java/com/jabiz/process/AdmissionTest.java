package com.jabiz.process;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The admission declaration (decision D45): the family's name, and the key as text. */
class AdmissionTest {

    record In(Object value) {}

    private static Admission<In> admission(String family) {
        return new Admission<>(family, In::value);
    }

    @Test
    void familiesAreShortLowerCaseNames() {
        assertThat(admission("qb.pub").validFamily()).isTrue();
        assertThat(admission("commerce.order").validFamily()).isTrue();
        assertThat(admission("a").validFamily()).isTrue();
        assertThat(admission("a-b_c.9").validFamily()).isTrue();
        assertThat(admission("a" + "b".repeat(47)).validFamily()).isTrue();
        assertThat(admission("a" + "b".repeat(48)).validFamily()).as("49 characters").isFalse();
        assertThat(admission("").validFamily()).isFalse();
        assertThat(admission("Qb.pub").validFamily()).isFalse();
        assertThat(admission("9pub").validFamily()).isFalse();
        assertThat(admission("qb:pub").validFamily()).as("the separator of the key").isFalse();
        assertThat(admission("qb pub").validFamily()).isFalse();
    }

    @Test
    void theKeyIsTheFamilyAndTheValueAsText() {
        UUID id = UUID.fromString("0190a5f0-0000-7000-8000-000000000001");
        assertThat(admission("qb.pub").keyOf(new In(id))).isEqualTo("qb.pub:" + id);
        assertThat(admission("qb.pub").keyOf(new In(42L))).isEqualTo("qb.pub:42");
        assertThat(admission("qb.pub").keyOf(new In("p-1"))).isEqualTo("qb.pub:p-1");
    }

    @Test
    void aNullOrBlankKeyDoesNotQueue() {
        assertThat(admission("qb.pub").keyOf(new In(null))).isNull();
        assertThat(admission("qb.pub").keyOf(new In(""))).isNull();
        assertThat(admission("qb.pub").keyOf(new In("  "))).isNull();
    }

    @Test
    void bothPartsAreRequired() {
        assertThatThrownBy(() -> new Admission<In>(null, In::value)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Admission<In>("qb.pub", null)).isInstanceOf(NullPointerException.class);
    }
}
