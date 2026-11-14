package com.jabiz.quizbuks.it;

import com.jabiz.context.EntryScope;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.ExposureSettlements;
import com.jabiz.quizbuks.operator.OperatorProcesses;
import com.jabiz.quizbuks.operator.QbOperators;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.context.Actor;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M8d: what the pages read: a Sponsor's publication's exposure fee and its settlements (only their own), the
 * operator's settlements and price (only the operator whose fee it was; a lead sees a price, anyone else of it a
 * member), and the headquarters' lists (finance only).
 */
class ExposureTemplatesIT extends ExposureFeeItSupport {

    static final ZoneId ZONE = ZoneId.of("Asia/Tokyo");

    private String hq() {
        return TestTokens.bearer(tokens, "fm8d-ops", QbPermissions.OPERATORS_WRITE, QbPermissions.OPERATORS_READ);
    }

    private String operatorSession(String userId, String operatorId) {
        Actor actor = new Actor(userId, null, Set.of(QbRoles.OPERATOR),
            Set.copyOf(QbRoles.of(QbRoles.OPERATOR).permissions()), clock.instant(), null, QbOperators.ENTRY, true)
            .inScope(new EntryScope(QbOperators.SCOPE, operatorId));
        return "Bearer " + tokens.issue(actor).token();
    }

    private String member(String hq, String operatorId, String name, boolean lead) {
        String userId = createUser(name);
        List<String> columns = query("SELECT column_name FROM information_schema.columns WHERE table_schema = "
            + "current_schema() AND table_name = 'sec_user_version' AND column_name NOT IN ('row_id', 'version_no', "
            + "'verified_email') ORDER BY ordinal_position").stream().map(row -> (String) row.get("column_name"))
            .toList();
        String list = String.join(", ", columns);
        execute("INSERT INTO sec_user_version (" + list + ", version_no, verified_email) SELECT " + list
            + ", version_no + 1, email FROM sec_user_version WHERE user_id = ?::uuid ORDER BY version_no DESC LIMIT 1",
            userId);
        run(OperatorProcesses.MEMBER_ADD, hq, body("operatorId", operatorId, "userId", userId, "lead", lead));
        return userId;
    }

    @Test
    void thePendingEstimateOfThePageIsTheSettlementsOwnRoundingAtAnyUnitAndCount() {
        autoApprove(true);
        String provider = reviewedSponsor("fm8d-est-provider");
        String sharedVideoId = approvedVideo(provider);
        String hq = TestTokens.bearer(tokens, "fm8-finance", QbPermissions.FEE_MANAGE);
        try {
            for (String unit : new String[] {"1.3333", "1.5", "2.0001", "99.9999"}) {
                String sponsor = reviewedSponsor("fm8d-est-" + unit.replace('.', 'p'));
                grant(sponsor, 100_000, clock.instant().plus(Duration.ofDays(400)));
                String operatorId = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
                    sponsor).getFirst().get("operator_id"));
                // The price the submission freezes: set before it, each unit its own publication.
                run(com.jabiz.quizbuks.fee.ExposurePrices.SET, hq,
                    body("operatorId", operatorId, "unit", unit, "reason", "F-M8d estimate test"));
                String publicationId = draft(sponsor, "fm8d-est-" + unit, sharedVideoId, 200L, clock.instant());
                submit(sponsorToken(sponsor), publicationId);
                int counted = 0;
                for (int target : new int[] {1, 3, 7, 30}) {
                    for (; counted < target; counted++) {
                        execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, "
                            + "provider_id, counted_at, surface, counted_sec, timer, capped_days, created_at, version) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 'START', 10, 'SCREEN', 0, ?, 1)",
                            UUID.randomUUID().toString(), publicationId, "fm8d-est-" + UUID.randomUUID(), sponsor,
                            provider, Timestamp.from(clock.instant()), Timestamp.from(clock.instant()));
                    }
                    Map<String, Object> fee = items("qb.sponsor.publication-exposure", sponsorToken(sponsor),
                        Map.of("params", Map.of("publicationId", publicationId))).getFirst();
                    // The page's estimate (SQL) and the settlement's own function (Java) say the same.
                    assertThat(dec(fee.get("pendingEstimate"))).as("%d counted at %s", target, unit)
                        .isEqualByComparingTo(com.jabiz.quizbuks.fee.ExposureFeeRules.cumulative(target,
                            new java.math.BigDecimal(unit)));
                }
            }
        } finally {
            // The price the other tests of the class expect.
            String operator = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
                reviewedSponsor("fm8d-est-reset")).getFirst().get("operator_id"));
            run(com.jabiz.quizbuks.fee.ExposurePrices.SET, hq, body("operatorId", operator, "unit", "1", "reason",
                "F-M8d estimate test: back"));
        }
    }

    @Test
    void thePagesReadTheirOwnFiguresAndNobodyElses() {
        autoApprove(true);
        String provider = reviewedSponsor("fm8d-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor("fm8d");
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(400)));
        String operatorId = String.valueOf(query("SELECT operator_id FROM qb_sponsor_operator WHERE user_id = ?",
            sponsor).getFirst().get("operator_id"));
        String publicationId = draft(sponsor, "fm8d", sharedVideoId, 200L, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        YearMonth month = YearMonth.parse(com.jabiz.quizbuks.fee.FeeRules.period(clock.instant(), ZONE));
        for (int i = 0; i < 30; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fm8d-" + UUID.randomUUID(), sponsor, provider, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        }

        // Before any settlement: bought 200 at 1 yen, 30 counted, 30 yen to come, no row yet.
        Map<String, Object> fee = items("qb.sponsor.publication-exposure", sponsorToken(sponsor),
            Map.of("params", Map.of("publicationId", publicationId))).getFirst();
        assertThat(dec(fee.get("unit"))).isEqualByComparingTo("1");
        assertThat(dec(fee.get("purchased"))).isEqualByComparingTo("200");
        assertThat(dec(fee.get("counted"))).isEqualByComparingTo("30");
        assertThat(dec(fee.get("pendingEstimate"))).isEqualByComparingTo("30");
        assertThat(dec(fee.get("settlements"))).isEqualByComparingTo("0");
        assertThat(String.valueOf(fee.get("lowRatio"))).isNotEmpty();
        // Another Sponsor reads nothing of it.
        String other = reviewedSponsor("fm8d-other");
        assertThat(items("qb.sponsor.publication-exposure", sponsorToken(other),
            Map.of("params", Map.of("publicationId", publicationId)))).isEmpty();

        clock.set(month.plusMonths(1).atDay(2).atTime(0, 47).atZone(ZONE).toInstant());
        systemRun(ExposureSettlements.SETTLE, body("publicationId", publicationId, "sponsorId", sponsor, "period",
            month.toString(), "last", false));
        Map<String, Object> settled = items("qb.sponsor.publication-exposure", sponsorToken(sponsor),
            Map.of("params", Map.of("publicationId", publicationId))).getFirst();
        assertThat(dec(settled.get("settledCount"))).isEqualByComparingTo("30");
        assertThat(dec(settled.get("charged"))).isEqualByComparingTo("30");
        assertThat(dec(settled.get("pendingEstimate"))).isEqualByComparingTo("0");
        assertThat(settled).containsEntry("finalSettled", false);
        assertThat(items("qb.sponsor.publication-exposure-settlements", sponsorToken(sponsor),
            Map.of("params", Map.of("publicationId", publicationId)))).singleElement().satisfies(row -> {
                assertThat(row).containsEntry("period", month.toString()).containsEntry("final", false);
                assertThat(dec(row.get("counted"))).isEqualByComparingTo("30");
                assertThat(dec(row.get("amount"))).isEqualByComparingTo("30");
            });
        assertThat(items("qb.sponsor.publication-exposure-settlements", sponsorToken(other),
            Map.of("params", Map.of("publicationId", publicationId)))).isEmpty();

        // The operator whose fee it is reads it; another operator, nothing; a member and the lead alike.
        String hq = hq();
        String lead = member(hq, operatorId, unique("fm8d-lead"), true);
        String otherOperator = newOperator(hq, unique("fm8d other"));
        String stranger = member(hq, otherOperator, unique("fm8d-stranger"), true);
        assertThat(items("qb.operator.exposure-settlements", operatorSession(lead, operatorId), Map.of()))
            .singleElement().satisfies(row -> {
                assertThat(row).containsEntry("publicationId", publicationId);
                assertThat(row).doesNotContainKey("operatorId");
            });
        assertThat(items("qb.operator.exposure-settlements", operatorSession(stranger, otherOperator), Map.of()))
            .isEmpty();
        assertThat(items("qb.operator.exposure-price", operatorSession(lead, operatorId), Map.of()))
            .singleElement().satisfies(row -> {
                assertThat(dec(row.get("unit"))).isEqualByComparingTo("1");
                assertThat(String.valueOf(row.get("range"))).contains("qb.exposure.unit.min");
            });

        // Headquarters: finance reads every settlement (and filters them); the operators do not.
        String finance = TestTokens.bearer(tokens, "fm8d-finance", QbPermissions.ADMIN_FINANCE_READ,
            QbPermissions.FEE_MANAGE);
        assertThat(items("qb.admin.exposure-settlements", finance, Map.of("params", Map.of("publicationId",
            publicationId)))).singleElement().satisfies(row -> assertThat(row).containsEntry("operatorId", operatorId));
        assertThat(items("qb.admin.exposure-settlements", finance, Map.of("params", Map.of("period", "1999-01"))))
            .isEmpty();
        assertThat(items("qb.admin.exposure-prices", finance, Map.of()))
            .anySatisfy(row -> assertThat(row).containsEntry("operatorId", operatorId));
        for (String id : List.of("qb.admin.exposure-settlements", "qb.admin.exposure-prices")) {
            client.post().uri("/api/queries/" + id).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, operatorSession(lead, operatorId)).bodyValue(Map.of())
                .exchange().expectStatus().isForbidden();
        }
    }

    @Test
    void theToDoListCountsTheRunningPublicationsWithLittleLeftOfTheExposuresBought() {
        autoApprove(true);
        String provider = reviewedSponsor("fm8d-low-provider");
        String sharedVideoId = approvedVideo(provider);
        String sponsor = reviewedSponsor("fm8d-low");
        grant(sponsor, 10_000, clock.instant().plus(Duration.ofDays(400)));
        String publicationId = draft(sponsor, "fm8d-low", sharedVideoId, 100L, clock.instant());
        submit(sponsorToken(sponsor), publicationId);
        java.util.function.Supplier<Object> low = () -> items("qb.sponsor.todo", sponsorToken(sponsor),
            Map.of("params", Map.of("at", clock.instant().toString()))).getFirst().get("exposureLow");
        assertThat(String.valueOf(low.get())).isEqualTo("0");
        for (int i = 0; i < 95; i++) {
            execute("INSERT INTO qb_exposure (exposure_id, publication_id, user_id, publisher_id, provider_id, "
                + "counted_at, surface, counted_sec, timer, capped_days, created_at, version) VALUES (?, ?, ?, ?, ?, "
                + "?, 'START', 10, 'SCREEN', 0, ?, 1)", UUID.randomUUID().toString(), publicationId,
                "fm8d-low-" + UUID.randomUUID(), sponsor, provider, Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()));
        }
        // 5 of 100 left, at or below a tenth (qb.exposure.low-ratio): counted; the reminder off (0): not.
        assertThat(String.valueOf(low.get())).isEqualTo("1");
        run("PARAM_SET", admin(), body("key", com.jabiz.quizbuks.setup.QbParams.EXPOSURE_LOW_RATIO, "value", "0"));
        try {
            assertThat(String.valueOf(low.get())).isEqualTo("0");
        } finally {
            run("PARAM_SET", admin(), body("key", com.jabiz.quizbuks.setup.QbParams.EXPOSURE_LOW_RATIO, "value", "0.1"));
        }
        assertThat(String.valueOf(low.get())).isEqualTo("1");
    }
}
