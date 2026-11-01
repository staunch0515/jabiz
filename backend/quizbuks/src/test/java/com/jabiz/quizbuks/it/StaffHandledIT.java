package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.publishing.RewardMethod;
import com.jabiz.quizbuks.publishing.RewardMethodProcesses;
import com.jabiz.quizbuks.reward.RedemptionProcesses;
import com.jabiz.quizbuks.reward.StaffProcesses;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who handed over a sponsor's prizes (Q74, docs/quizbuks/plans/Q74-staff-handled.md): a staff member's own list and
 * totals in the App, through their present place only; the sponsor's figures by handler, staff that left included, and
 * the list filtered by handler.
 */
class StaffHandledIT extends PlayItSupport {

    private void allowRedeem(String sponsor) {
        String admin = TestTokens.bearer(tokens, "q74-admin", QbPermissions.ADMIN_REWARD_METHODS,
            QbPermissions.ADMIN_USERS_READ, QbPermissions.ADMIN_PUBLICATION_READ);
        run(RewardMethodProcesses.SET, admin, body("userId", sponsor, "methods",
            List.of(RewardMethod.PLATFORM, RewardMethod.OFFLINE_REDEEM), "note", "Q74 test"));
    }

    private String prizePublication(String sponsor) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("regions", List.of("GLOBAL"));
        overrides.put("rewardMethod", RewardMethod.OFFLINE_REDEEM);
        overrides.put("redeemDescription", "A coffee at our counter");
        return livePublication(sponsor, "Handled coffee", overrides);
    }

    /** A taker wins the publication's prize and collects it with the code. */
    @SuppressWarnings("unchecked")
    private void collect(String publicationId, String key) {
        String token = taker(newTaker(unique("t-q74"), "JP"));
        String attemptId = (String) start(token, publicationId).get("attemptId");
        clock.advance(Duration.ofSeconds(1));
        assertThat(submit(token, attemptId, answers(true, true))).containsEntry("rewardStatus", "REDEEM");
        deliverEvents();
        String prize = (String) ((List<Map<String, Object>>) run(RedemptionProcesses.CHECK, token,
            body("scannedKey", key)).get("prizes")).getFirst().get("redemptionId");
        assertThat(run(RedemptionProcesses.CLAIM, token, body("scannedKey", key, "redemptionId", prize)))
            .containsEntry("status", "REDEEMED");
    }

    private List<Map<String, Object>> rows(String template, String token, Map<String, Object> params) {
        return items(template, token, Map.of("params", params, "limit", 200));
    }

    private static Map<String, Object> at(Object instant, Object... more) {
        Map<String, Object> params = new LinkedHashMap<>(Map.of("at", instant.toString()));
        for (int i = 0; i < more.length; i += 2) {
            params.put((String) more[i], more[i + 1]);
        }
        return params;
    }

    private static long number(Object value) {
        return new BigDecimal(String.valueOf(value)).longValue();
    }

    @Test
    void aStaffMemberSeesWhatTheyHandedOverAndTheSponsorSeesItByHandler() {
        String sponsor = newSponsor(unique("s-q74"));
        allowRedeem(sponsor);
        String sponsorToken = sponsorToken(sponsor);
        String publicationId = prizePublication(sponsor);
        String kaiId = newTaker(unique("t-q74-kai"), "JP");
        run(StaffProcesses.ADD, sponsorToken, body("userId", kaiId));
        String kai = taker(kaiId);

        // Kai hands over two prizes with their rotating code, the sponsor one with theirs.
        String kaiKey = (String) run(StaffProcesses.ISSUE, kai, body()).get("redeemKey");
        collect(publicationId, kaiKey);
        collect(publicationId, kaiKey);
        String own = (String) run(RedemptionProcesses.ISSUE, sponsorToken, body("kind", "ROTATING")).get("redeemKey");
        collect(publicationId, own);
        long value = number(query("SELECT sum(amount) AS v FROM (SELECT DISTINCT ON (redemption_id) amount, "
            + "redeemed_by FROM qb_redemption_version ORDER BY redemption_id, version_no DESC) r "
            + "WHERE redeemed_by = ?", kaiId).getFirst().get("v"));

        // Kai's own: the two, in every period that has today, none in a day ten days on but still in all.
        Object now = clock.instant();
        assertThat(rows("qb.app.staff-handled", kai, at(now))).hasSize(2).allSatisfy(row -> assertThat(row)
            .containsEntry("publicationName", "Handled coffee").containsEntry("takerName", "Taker JP")
            .containsKey("amount"));
        assertThat(rows("qb.app.staff-handled", kai, at(now, "period", "TODAY"))).hasSize(2);
        Object later = clock.instant().plus(Duration.ofDays(10));
        assertThat(rows("qb.app.staff-handled", kai, at(later, "period", "WEEK"))).isEmpty();
        assertThat(rows("qb.app.staff-handled", kai, at(later, "period", "ALL"))).hasSize(2);
        assertThat(rows("qb.app.staff-handled-totals", kai, at(now))).singleElement().satisfies(row -> {
            assertThat(number(row.get("todayCount"))).isEqualTo(2);
            assertThat(number(row.get("weekCount"))).isEqualTo(2);
            assertThat(number(row.get("monthCount"))).isEqualTo(2);
            assertThat(number(row.get("todayValue"))).isEqualTo(value);
            assertThat(row).containsKey("zone");
        });
        assertThat(rows("qb.app.staff-handled-totals", kai, at(later))).singleElement()
            .satisfies(row -> assertThat(number(row.get("todayCount"))).isZero());

        // The sponsor: by handler, the sponsor themselves marked; the list filtered by either.
        List<Map<String, Object>> handlers = rows("qb.sponsor.redemption-handlers", sponsorToken, at(now));
        assertThat(handlers).hasSize(2);
        assertThat(handlers).filteredOn(row -> Boolean.TRUE.equals(row.get("self"))).singleElement()
            .satisfies(row -> assertThat(number(row.get("totalCount"))).isEqualTo(1));
        assertThat(handlers).filteredOn(row -> kaiId.equals(row.get("handlerId"))).singleElement()
            .satisfies(row -> {
                assertThat(row).containsEntry("name", "Taker JP").containsEntry("self", false);
                assertThat(number(row.get("todayCount"))).isEqualTo(2);
                assertThat(number(row.get("totalValue"))).isEqualTo(value);
            });
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of("handledBy", kaiId))).hasSize(2)
            .allSatisfy(row -> assertThat(row).containsEntry("redeemedBy", kaiId).containsEntry("handledBySelf", false)
                .containsEntry("redeemedByName", "Taker JP"));
        assertThat(rows("qb.sponsor.redemptions", sponsorToken, Map.of("handledBy", "SELF"))).singleElement()
            .satisfies(row -> assertThat(row).containsEntry("handledBySelf", true));

        // Another sponsor sees none of it; a taker on no staff reads nothing.
        String other = sponsorToken(newSponsor(unique("s-q74-other")));
        assertThat(rows("qb.sponsor.redemption-handlers", other, at(now))).isEmpty();
        String stranger = taker(newTaker(unique("t-q74-none"), "JP"));
        assertThat(rows("qb.app.staff-handled", stranger, at(now))).isEmpty();
        assertThat(rows("qb.app.staff-handled-totals", stranger, at(now))).isEmpty();

        // Kai leaves the staff: Kai sees nothing of this sponsor any more (D-Q74-1); the sponsor's figures keep Kai's.
        run(StaffProcesses.REMOVE, sponsorToken, body("userId", kaiId));
        assertThat(rows("qb.app.staff-handled", kai, at(now))).isEmpty();
        assertThat(rows("qb.app.staff-handled-totals", kai, at(now))).isEmpty();
        assertThat(rows("qb.sponsor.redemption-handlers", sponsorToken, at(now)))
            .anySatisfy(row -> assertThat(row).containsEntry("handlerId", kaiId));
    }
}
