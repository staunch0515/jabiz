package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValuesTest {

    @Test
    void readsWholeNumbersOfAnyKind() {
        assertThat(Values.longValue(new BigDecimal("12"))).isEqualTo(12);
        assertThat(Values.longValue(new BigDecimal("12.00"))).isEqualTo(12);
        assertThat(Values.longValue(7)).isEqualTo(7);
        assertThat(Values.longValue(7L)).isEqualTo(7);
        assertThat(Values.longValue(3.0d)).isEqualTo(3);
        assertThat(Values.longValue(null)).isZero();
        assertThat(Values.intValue(new BigDecimal("9999"))).isEqualTo(9999);
    }

    @Test
    void refusesFractionsAndWhatIsNoNumber() {
        assertThatThrownBy(() -> Values.longValue(new BigDecimal("1.5"))).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Values.longValue(2.5d)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Values.intValue(Long.MAX_VALUE)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Values.longValue("3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsKeys() {
        UUID id = UUID.randomUUID();
        assertThat(Values.uuid(id)).isSameAs(id);
        assertThat(Values.uuid(id.toString())).isEqualTo(id);
        assertThat(Values.uuid(null)).isNull();
    }
}
