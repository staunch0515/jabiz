package com.jabiz.quizbuks.identity;

import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Q71: what a taker is told before asking for the deletion of their account (they inform, never refuse). */
class TakerErasureTest {

    /** F-M15 a: the loads count the open ones (their statuses: the loads' test below). */
    private static List<ErasureNotice> notices(long withdrawals, long cash, long vouchers) {
        return TakerErasure.notices(new ErasureSubject("u1", Map.of(), Map.of(TakerErasure.TRANSFERS, withdrawals,
            TakerErasure.CASH, cash, TakerErasure.REDEMPTIONS, vouchers), Map.of()));
    }

    @Test
    void tellOfWithdrawalsUnderWayAndRewardsNotYetReceived() {
        List<ErasureNotice> notices = notices(2, 1, 1);
        assertThat(notices).extracting(ErasureNotice::code)
            .containsExactly(TakerErasure.NOTICE_WITHDRAWAL, TakerErasure.NOTICE_REWARDS);
        assertThat(notices.get(0).params()).containsEntry("count", 2L);
        assertThat(notices.get(1).params()).containsEntry("count", 2L);
    }

    @Test
    void sayNothingWhenNothingIsOpen() {
        assertThat(notices(0, 0, 0)).isEmpty();
    }

    @Test
    void theLoadsCountOnlyTheOpenOnes() {
        Map<String, ErasureLoad> loads = new TakerErasure().qbTakerPersonalData().loads().stream()
            .collect(Collectors.toMap(ErasureLoad::name, load -> load));
        assertThat(loads.values()).allSatisfy(load -> assertThat(load.kind()).isEqualTo(ErasureLoad.Kind.COUNT));
        assertThat(statuses(loads.get(TakerErasure.TRANSFERS))).isEqualTo(TakerErasure.OPEN_TRANSFERS);
        assertThat(statuses(loads.get(TakerErasure.CASH))).isEqualTo(TakerErasure.OPEN_CASH);
        assertThat(statuses(loads.get(TakerErasure.REDEMPTIONS))).isEqualTo(TakerErasure.OPEN_REDEMPTIONS);
    }

    static Set<Object> statuses(ErasureLoad load) {
        return load.conditions().stream().filter(condition -> condition.field().equals("status"))
            .flatMap(condition -> condition.values().stream()).collect(Collectors.toSet());
    }
}
