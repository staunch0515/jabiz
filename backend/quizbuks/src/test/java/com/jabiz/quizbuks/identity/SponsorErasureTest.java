package com.jabiz.quizbuks.identity;

import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Q72: what a sponsor is told before asking for the deletion of their account (they inform, never refuse). */
class SponsorErasureTest {

    private static List<ErasureNotice> notices(Map<String, List<Map<String, Object>>> data) {
        return SponsorErasure.notices(new ErasureSubject("u1", data));
    }

    @Test
    void tellOfRunningPublicationsABalanceAndRewardsStillOwed() {
        List<ErasureNotice> notices = notices(Map.of(
            SponsorErasure.PUBLICATIONS, List.of(
                Map.of("publisherKind", "SPONSOR", "status", "LIVE"),
                Map.of("publisherKind", "SPONSOR", "status", "PAUSED"),
                Map.of("publisherKind", "SPONSOR", "status", "ENDED"),
                Map.of("publisherKind", "SHOPIFY_SHOP", "status", "LIVE")),
            SponsorErasure.WALLETS, List.of(Map.of("balance", 1200)),
            SponsorErasure.CASH, List.of(Map.of("status", "DUE"), Map.of("status", "CONFIRMED"))));
        assertThat(notices).extracting(ErasureNotice::code).containsExactly(SponsorErasure.NOTICE_PUBLICATIONS,
            SponsorErasure.NOTICE_BALANCE, SponsorErasure.NOTICE_CASH);
        assertThat(notices.get(0).params()).containsEntry("count", 2L);
        assertThat(notices.get(1).params()).containsEntry("balance", "1200");
        assertThat(notices.get(2).params()).containsEntry("count", 1L);
    }

    @Test
    void sayNothingWhenNothingIsLeft() {
        assertThat(notices(Map.of(SponsorErasure.WALLETS, List.of(Map.of("balance", 0))))).isEmpty();
        assertThat(notices(Map.of())).isEmpty();
    }
}
