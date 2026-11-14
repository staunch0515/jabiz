package com.jabiz.quizbuks.content.ai;

import com.jabiz.quizbuks.fee.AiFeeRules;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #707 review M3: the one room of the request, its quotes, the standing and the return. A credit that holds something
 * with no lots read is a defect (an internal error), never a room of 0 that would refuse a request for credit short.
 */
class AiFeesRoomTest {

    private static final Instant NOW = Instant.parse("2026-11-14T00:00:00Z");
    private static final BigDecimal TAX = new BigDecimal("0.10");

    @Test
    void aCreditThatHoldsSomethingWithNoLotsReadIsADefect() {
        AiFees.Standing standing = new AiFees.Standing(BigDecimal.valueOf(1_000), Map.of(), BigDecimal.ZERO);
        assertThatThrownBy(() -> AiFees.room(standing, true, List.of(), TAX, NOW))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("lots were not read");
    }

    @Test
    void cashHeldIsAllRoomAndAnEmptyCreditHasNone() {
        AiFees.Standing standing = new AiFees.Standing(BigDecimal.valueOf(1_000), Map.of(), BigDecimal.valueOf(200));
        AiFees.Room room = AiFees.room(standing, true, List.of(new AiFeeRules.Lot(false, "cash", BigDecimal.valueOf(1_000),
            null, false)), TAX, NOW);
        assertThat(room.available()).isEqualByComparingTo("800");
        assertThat(room.returnable()).isEqualByComparingTo("800");

        AiFees.Room none = AiFees.room(new AiFees.Standing(BigDecimal.ZERO, Map.of(), BigDecimal.ZERO), false,
            List.of(), TAX, NOW);
        assertThat(none.available()).isZero();
        assertThat(none.returnable()).isZero();
    }

    @Test
    void expiredGrantedCreditBeyondWhatTheJobsHoldIsNoRoom() {
        AiFees.Standing standing = new AiFees.Standing(BigDecimal.valueOf(4_000), Map.of(), BigDecimal.valueOf(500));
        AiFees.Room room = AiFees.room(standing, true, List.of(
            new AiFeeRules.Lot(true, "grant", BigDecimal.valueOf(3_000), NOW.minusSeconds(60), false),
            new AiFeeRules.Lot(false, "cash", BigDecimal.valueOf(1_000), null, false)), TAX, NOW);
        assertThat(room.returnable()).isEqualByComparingTo("1000");
        assertThat(room.available()).isEqualByComparingTo("1000");
    }
}
