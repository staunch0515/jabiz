package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.wallet.WalletCodes;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The new sponsors' top-up limit (Q10b, review M3; S-3): before and for {@code qb.topup.new-sponsor.days} (30) days
 * after the first paid top-up, the paid and unfinished top-ups with the new one stay within
 * {@code qb.topup.new-sponsor.max-total} (100 000); the sponsor's page reads the same figures.
 */
class TopUpLimitIT extends TopUpItSupport {

    private String paidTopUp(String sponsor, long amount) {
        String topUpId = openTopUp(sponsor, amount);
        deliver(event(eventId(), "checkout.session.completed", completed(topUpId)));
        assertThat(topUp(topUpId)).containsEntry("status", "PAID");
        return topUpId;
    }

    private Map<String, Object> limit(String sponsor) {
        return items("qb.sponsor.topup-limit", sponsorToken(sponsor), Map.of("params",
            Map.of("at", now().toString()))).getFirst();
    }

    @Test
    void aNewSponsorTopsUpWithinTheLimitUntilThePeriodEnds() {
        String sponsor = newSponsor("s-limit");
        // Before the first paid top-up the limit applies too; its end is not known yet.
        Map<String, Object> refused = refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 100_001), 422);
        assertThat(codes(refused)).containsExactly(WalletCodes.TOPUP_NEW_SPONSOR_LIMIT);
        assertThat((String) violations(refused).getFirst().get("message")).contains("100000");
        assertThat(limit(sponsor)).containsEntry("limited", true).containsEntry("until", null);
        assertThat(money(limit(sponsor).get("remaining"))).isEqualByComparingTo("100000");

        String first = paidTopUp(sponsor, 60_000);
        Instant until = instant(topUp(first).get("paid_at")).plus(Duration.ofDays(30));
        // 40 000 remain; an unfinished top-up counts as well.
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 40_001), 422)))
            .containsExactly(WalletCodes.TOPUP_NEW_SPONSOR_LIMIT);
        String open = openTopUp(sponsor, 30_000);
        Map<String, Object> over = refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 10_001), 422);
        assertThat(codes(over)).containsExactly(WalletCodes.TOPUP_NEW_SPONSOR_LIMIT);
        assertThat((String) violations(over).getFirst().get("message")).contains("10000 Kudos remain");
        Map<String, Object> shown = limit(sponsor);
        assertThat(shown).containsEntry("limited", true);
        assertThat(money(shown.get("remaining"))).isEqualByComparingTo("10000");
        assertThat(money(shown.get("maxTotal"))).isEqualByComparingTo("100000");
        assertThat(instant(Instant.parse(String.valueOf(shown.get("until"))))).isEqualTo(until);
        assertThat(money(shown.get("min"))).isEqualByComparingTo("1000");
        assertThat(money(shown.get("max"))).isEqualByComparingTo("1000000");
        startTopUp(sponsor, 10_000);
        assertThat(money(limit(sponsor).get("remaining"))).isEqualByComparingTo("0");

        // The open checkout expires: it no longer counts.
        advance(Duration.ofDays(2));
        assertThat(money(limit(sponsor).get("remaining"))).isEqualByComparingTo("40000");
        assertThat(topUp(open)).containsEntry("paid_at", null);

        // The period ends 30 days after the first paid top-up: only the single top-up's maximum is left.
        clockAt(until.minusSeconds(1));
        assertThat(codes(refused("QB_TOPUP_START", sponsorToken(sponsor), body("amount", 40_001), 422)))
            .containsExactly(WalletCodes.TOPUP_NEW_SPONSOR_LIMIT);
        clockAt(until);
        assertThat(limit(sponsor)).containsEntry("limited", false).containsEntry("remaining", null);
        startTopUp(sponsor, 1_000_000);
    }

    @Test
    void startsAtOnceTakeTurns() throws Exception {
        String sponsor = newSponsor("s-limit-race");
        String token = sponsorToken(sponsor);
        // Warm the JSON of the input and the problem up before racing (BlockHound; see PlayItSupport.warmUp).
        refused("QB_TOPUP_START", token, body("amount", 100_001), 422);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                calls.add(() -> client.post().uri("/api/processes/QB_TOPUP_START/latest")
                    .contentType(MediaType.APPLICATION_JSON).header(HttpHeaders.AUTHORIZATION, token)
                    .bodyValue(body("amount", 60_000)).exchange().expectBody(MAP).returnResult().getStatus()
                    .value());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> status : pool.invokeAll(calls)) {
                statuses.add(status.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 422, 422);
        } finally {
            pool.shutdown();
        }
        assertThat(query("SELECT count(*) AS n FROM qb_topup WHERE sponsor_id = ?", sponsor).getFirst())
            .containsEntry("n", 1L);
    }

    private void clockAt(Instant at) {
        clock.set(at);
    }
}
