package com.jabiz.quizbuks.identity;

import com.jabiz.quizbuks.fee.FeeRules;
import com.jabiz.quizbuks.fee.QbFees;
import com.jabiz.quizbuks.fee.QbPrepayments;
import com.jabiz.quizbuks.publishing.PublicationAdmin;
import com.jabiz.quizbuks.publishing.PublisherKind;
import com.jabiz.quizbuks.publishing.QbPublications;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.wallet.QbWallets;
import com.jabiz.quizbuks.wallet.TopUpStatus;
import com.jabiz.security.erasure.ErasureLoad;
import com.jabiz.security.erasure.ErasureNotice;
import com.jabiz.security.erasure.ErasureSubject;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Q72: what a sponsor is told before asking for the deletion of their account (they inform, never refuse). */
class SponsorErasureTest {

    /** F-M15 a: publications and cash rewards are counts of the open ones (their conditions: the loads' test below). */
    private static List<ErasureNotice> notices(long running, List<Map<String, Object>> wallets, long owed) {
        return notices(wallets, Map.of(SponsorErasure.PUBLICATIONS, running, SponsorErasure.CASH, owed), Map.of());
    }

    /** The wallets' rows, and the counts and totals given (every other count and total 0). */
    private static List<ErasureNotice> notices(List<Map<String, Object>> wallets, Map<String, Long> counts,
        Map<String, Long> sums) {
        Map<String, Long> allCounts = new HashMap<>();
        for (String load : List.of(SponsorErasure.PUBLICATIONS, SponsorErasure.CASH, SponsorErasure.UNPAID,
            SponsorErasure.POSTPAID, SponsorErasure.DEPOSITS, SponsorErasure.FROZEN, SponsorErasure.TOPUPS)) {
            allCounts.put(load, 0L);
        }
        allCounts.putAll(counts);
        Map<String, BigDecimal> allSums = new HashMap<>();
        for (String load : List.of(SponsorErasure.UNPAID_TOTAL, SponsorErasure.UNPAID_CREDITED,
            SponsorErasure.UNPAID_PAID, SponsorErasure.DEPOSITS_LEFT)) {
            allSums.put(load, BigDecimal.ZERO);
        }
        sums.forEach((load, sum) -> allSums.put(load, BigDecimal.valueOf(sum)));
        return SponsorErasure.notices(new ErasureSubject("u1", Map.of(SponsorErasure.WALLETS, wallets), allCounts,
            allSums));
    }

    private static Map<String, Object> wallet(String kind, long balance, long held) {
        return Map.of("kind", kind, "balance", balance, "held", held);
    }

    private static Map<String, ErasureNotice> byCode(List<ErasureNotice> notices) {
        Map<String, ErasureNotice> found = new LinkedHashMap<>();
        notices.forEach(notice -> found.put(notice.code(), notice));
        return found;
    }

    @Test
    void tellOfRunningPublicationsABalanceAndRewardsStillOwed() {
        List<ErasureNotice> notices = notices(2, List.of(wallet(QbWallets.SPONSOR, 1200, 0)), 1);
        assertThat(notices).extracting(ErasureNotice::code).containsExactly(SponsorErasure.NOTICE_PUBLICATIONS,
            SponsorErasure.NOTICE_BALANCE, SponsorErasure.NOTICE_CASH);
        assertThat(notices.get(0).params()).containsEntry("count", 2L);
        assertThat(notices.get(1).params()).containsEntry("balance", "1200");
        assertThat(notices.get(2).params()).containsEntry("count", 1L);
    }

    @Test
    void sayNothingWhenNothingIsLeft() {
        assertThat(notices(0, List.of(wallet(QbWallets.SPONSOR, 0, 0), wallet(QbWallets.CASH, 0, 0),
            wallet(QbWallets.FEE_CREDIT, 0, 0)), 0)).isEmpty();
        assertThat(notices(0, List.of(), 0)).isEmpty();
    }

    @Test
    void theBalanceIsTheKudosWalletsOnlyTheCashAndTheGrantedCreditAreToldApart() {
        Map<String, ErasureNotice> notices = byCode(notices(0, List.of(wallet(QbWallets.SPONSOR, 1200, 0),
            wallet(QbWallets.TAKER, 300, 0), wallet(QbWallets.CASH, 4000, 0), wallet(QbWallets.FEE_CREDIT, 3500, 0)),
            0));
        assertThat(notices.get(SponsorErasure.NOTICE_BALANCE).params()).containsEntry("balance", "1500");
        assertThat(notices.get(SponsorErasure.NOTICE_CASH_LEFT).params()).containsEntry("amount", "4000");
        assertThat(notices.get(SponsorErasure.NOTICE_GIFT_VOID).params()).containsEntry("amount", "3500");
        assertThat(notices).doesNotContainKeys(SponsorErasure.NOTICE_FEE_UNPAID, SponsorErasure.NOTICE_FEE_SHORTFALL);
    }

    /** The plan's example (F-M15 §13 question 4): granted 3,500 and cash 4,000 against 8,100 owed, 600 short. */
    @Test
    void unpaidInvoicesAndWhatTheAccountMayNotCover() {
        Map<String, ErasureNotice> notices = byCode(notices(
            List.of(wallet(QbWallets.CASH, 4500, 500), wallet(QbWallets.FEE_CREDIT, 3500, 0)),
            Map.of(SponsorErasure.UNPAID, 3L),
            Map.of(SponsorErasure.UNPAID_TOTAL, 9000L, SponsorErasure.UNPAID_CREDITED, 400L,
                SponsorErasure.UNPAID_PAID, 500L)));
        assertThat(notices.get(SponsorErasure.NOTICE_FEE_UNPAID).params()).containsEntry("count", 3L)
            .containsEntry("amount", "8100");
        // The cash held by a dispute does not pay.
        assertThat(notices.get(SponsorErasure.NOTICE_FEE_SHORTFALL).params()).containsEntry("available", "7500")
            .containsEntry("short", "600");
    }

    @Test
    void aPostpaidSponsorIsToldOfFeesNotYetInvoicedAndEnoughIsNoShortfall() {
        Map<String, ErasureNotice> notices = byCode(notices(List.of(wallet(QbWallets.CASH, 1000, 0)),
            Map.of(SponsorErasure.POSTPAID, 1L), Map.of()));
        assertThat(notices.get(SponsorErasure.NOTICE_FEE_UNPAID).params()).containsEntry("count", 0L)
            .containsEntry("amount", "0");
        assertThat(notices).doesNotContainKey(SponsorErasure.NOTICE_FEE_SHORTFALL);
        Map<String, ErasureNotice> covered = byCode(notices(List.of(wallet(QbWallets.FEE_CREDIT, 1000, 0)),
            Map.of(SponsorErasure.UNPAID, 1L), Map.of(SponsorErasure.UNPAID_TOTAL, 1000L)));
        assertThat(covered).containsKey(SponsorErasure.NOTICE_FEE_UNPAID)
            .doesNotContainKey(SponsorErasure.NOTICE_FEE_SHORTFALL);
    }

    @Test
    void prepaymentsHeldPendingTopUpsAndGrantedCreditFrozenInThem() {
        Map<String, ErasureNotice> notices = byCode(notices(List.of(),
            Map.of(SponsorErasure.DEPOSITS, 2L, SponsorErasure.FROZEN, 3L, SponsorErasure.TOPUPS, 1L),
            Map.of(SponsorErasure.DEPOSITS_LEFT, 3000L)));
        assertThat(notices.get(SponsorErasure.NOTICE_DEPOSITS).params()).containsEntry("count", 2L)
            .containsEntry("amount", "3000");
        // Nothing in the wallet of granted credit, but some may be frozen: told, without a figure for it.
        assertThat(notices.get(SponsorErasure.NOTICE_GIFT_VOID).params()).containsEntry("amount", "0");
        assertThat(notices.get(SponsorErasure.NOTICE_TOPUPS).params()).containsEntry("count", 1L);
        // An AI credit alone holds no publication's prepayment, but may hold granted credit.
        Map<String, ErasureNotice> ai = byCode(notices(List.of(), Map.of(SponsorErasure.FROZEN, 1L), Map.of()));
        assertThat(ai).containsOnlyKeys(SponsorErasure.NOTICE_GIFT_VOID);
    }

    @Test
    void theFeeAccountsLoadsReadOnlyWhatIsOpen() {
        Map<String, ErasureLoad> loads = new SponsorErasure().qbSponsorPersonalData().loads().stream()
            .collect(Collectors.toMap(ErasureLoad::name, load -> load));
        for (String name : List.of(SponsorErasure.UNPAID, SponsorErasure.UNPAID_TOTAL, SponsorErasure.UNPAID_CREDITED,
            SponsorErasure.UNPAID_PAID)) {
            assertThat(values(loads.get(name), "status")).isEqualTo(Set.copyOf(QbFees.UNPAID));
        }
        assertThat(loads.get(SponsorErasure.UNPAID_TOTAL).field()).isEqualTo("total");
        assertThat(values(loads.get(SponsorErasure.POSTPAID), "billingMode")).containsExactly(FeeRules.POSTPAID);
        for (String name : List.of(SponsorErasure.DEPOSITS, SponsorErasure.DEPOSITS_LEFT, SponsorErasure.FROZEN)) {
            assertThat(values(loads.get(name), "status")).containsExactly(QbPrepayments.OPEN);
            assertThat(values(loads.get(name), "mode")).containsExactly(QbPrepayments.PREPAID);
        }
        assertThat(values(loads.get(SponsorErasure.DEPOSITS), "purpose")).doesNotContain(QbPrepayments.AI)
            .isEqualTo(values(loads.get(SponsorErasure.DEPOSITS_LEFT), "purpose"));
        assertThat(values(loads.get(SponsorErasure.FROZEN), "purpose")).isEmpty();
        assertThat(values(loads.get(SponsorErasure.TOPUPS), "status")).containsExactlyInAnyOrder(TopUpStatus.OPENING,
            TopUpStatus.OPEN, TopUpStatus.AWAITING, TopUpStatus.REPORTED, TopUpStatus.REVIEW);
        assertThat(loads.values()).filteredOn(load -> load.kind() == ErasureLoad.Kind.ROWS)
            .extracting(ErasureLoad::name).containsExactly(SponsorErasure.WALLETS);
    }

    /**
     * The postpaid notice reads only a billing mode written on the onboarding row: right only while the platform's
     * default is PREPAID. Changing the default must change the load (SponsorErasure.POSTPAID) too.
     */
    @Test
    void theDefaultBillingModeIsPrepaidAsThePostpaidLoadAssumes() {
        Object declared = QbParams.ALL.stream().filter(param -> param.key().equals(QbParams.FEE_DEFAULT_BILLING_MODE))
            .findFirst().orElseThrow().value();
        assertThat(declared).isEqualTo(FeeRules.PREPAID);
        assertThat(FeeRules.billingModeOf(null, null)).isEqualTo(FeeRules.PREPAID);
    }

    @Test
    void theLoadsCountOnlyTheSponsorsOpenOnes() {
        Map<String, ErasureLoad> loads = new SponsorErasure().qbSponsorPersonalData().loads().stream()
            .collect(Collectors.toMap(ErasureLoad::name, load -> load));
        ErasureLoad publications = loads.get(SponsorErasure.PUBLICATIONS);
        assertThat(publications.kind()).isEqualTo(ErasureLoad.Kind.COUNT);
        assertThat(publications.dataset()).isEqualTo(QbPublications.defaultDataset(QbPublications.CARD));
        assertThat(values(publications, "publisherKind")).containsExactly(PublisherKind.SPONSOR);
        assertThat(values(publications, "status")).isEqualTo(Set.copyOf(PublicationAdmin.ENDABLE));
        ErasureLoad cash = loads.get(SponsorErasure.CASH);
        assertThat(cash.kind()).isEqualTo(ErasureLoad.Kind.COUNT);
        assertThat(values(cash, "status")).isEqualTo(SponsorErasure.OPEN_CASH);
    }

    static Set<Object> values(ErasureLoad load, String field) {
        return load.conditions().stream().filter(condition -> condition.field().equals(field))
            .flatMap(condition -> condition.values().stream()).collect(Collectors.toSet());
    }
}
