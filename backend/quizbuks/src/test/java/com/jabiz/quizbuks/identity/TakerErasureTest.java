package com.jabiz.quizbuks.identity;

import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Q71: what a taker is told before asking for the deletion of their account (they inform, never refuse). */
class TakerErasureTest {

    private static List<ErasureNotice> notices(Map<String, List<Map<String, Object>>> data) {
        return TakerErasure.notices(new ErasureSubject("u1", data));
    }

    @Test
    void tellOfWithdrawalsUnderWayAndRewardsNotYetReceived() {
        List<ErasureNotice> notices = notices(Map.of(
            TakerErasure.TRANSFERS, List.of(Map.of("status", "SENDING"), Map.of("status", "REVIEW"),
                Map.of("status", "SENT"), Map.of("status", "FAILED")),
            TakerErasure.CASH, List.of(Map.of("status", "DUE"), Map.of("status", "CONFIRMED")),
            TakerErasure.REDEMPTIONS, List.of(Map.of("status", "CLAIMED"), Map.of("status", "REDEEMED"))));
        assertThat(notices).extracting(ErasureNotice::code)
            .containsExactly(TakerErasure.NOTICE_WITHDRAWAL, TakerErasure.NOTICE_REWARDS);
        assertThat(notices.get(0).params()).containsEntry("count", 2L);
        assertThat(notices.get(1).params()).containsEntry("count", 2L);
    }

    @Test
    void sayNothingWhenNothingIsOpen() {
        assertThat(notices(Map.of(TakerErasure.TRANSFERS, List.of(Map.of("status", "SENT")),
            TakerErasure.REDEMPTIONS, List.of(Map.of("status", "EXPIRED"))))).isEmpty();
        assertThat(notices(Map.of())).isEmpty();
    }
}
