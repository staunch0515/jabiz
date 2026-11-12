package com.jabiz.quizbuks.fee;

import com.jabiz.entity.Violation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PrepaymentRulesTest {

    static PrepaymentRules.Item audience(long amount) {
        return new PrepaymentRules.Item(QbPrepayments.AUDIENCE, BigDecimal.valueOf(amount),
            BigDecimal.valueOf(amount), "{}");
    }

    static PrepaymentRules.Standing prepaid(long spendable) {
        return new PrepaymentRules.Standing(FeeRules.PREPAID, BigDecimal.valueOf(spendable), null, null, null);
    }

    static PrepaymentRules.Standing postpaid(long owed, long setAside, long limit) {
        return new PrepaymentRules.Standing(FeeRules.POSTPAID, null, BigDecimal.valueOf(owed),
            BigDecimal.valueOf(setAside), BigDecimal.valueOf(limit));
    }

    @Test
    void aPrepaidSponsorNeedsTheWholeTotalInTheAccount() {
        PrepaymentRules.Funds funds = PrepaymentRules.check(List.of(audience(770), audience(30)), prepaid(799));
        assertThat(funds.total()).isEqualByComparingTo("800");
        assertThat(funds.available()).isEqualByComparingTo("799");
        assertThat(funds.shortfall()).isEqualByComparingTo("1");
        assertThat(funds.enough()).isFalse();
        assertThat(PrepaymentRules.check(List.of(audience(800)), prepaid(800)).enough()).isTrue();
    }

    @Test
    void aPostpaidSponsorsLimitCountsWhatTheyOweAndWhatIsSetAside() {
        // 1,200 less 500 owed less 300 set aside leaves 400.
        PrepaymentRules.Funds funds = PrepaymentRules.check(List.of(audience(401)), postpaid(500, 300, 1200));
        assertThat(funds.available()).isEqualByComparingTo("400");
        assertThat(funds.shortfall()).isEqualByComparingTo("1");
        assertThat(PrepaymentRules.check(List.of(audience(400)), postpaid(500, 300, 1200)).enough()).isTrue();
        // Over the limit already: nothing is available, never negative.
        assertThat(PrepaymentRules.check(List.of(audience(1)), postpaid(2000, 0, 1200)).available())
            .isEqualByComparingTo("0");
    }

    @Test
    void nothingSetAsideIsAlwaysEnoughAndZeroItemsAreLeftOut() {
        PrepaymentRules.Funds funds = PrepaymentRules.check(List.of(audience(0)), prepaid(0));
        assertThat(funds.enough()).isTrue();
        assertThat(funds.items()).isEmpty();
        assertThat(PrepaymentRules.refusal(funds)).isNull();
        assertThat(PrepaymentRules.check(List.of(), postpaid(5000, 0, 0)).enough()).isTrue();
    }

    @Test
    void theRefusalCarriesEveryAmount() {
        Violation refusal = PrepaymentRules.refusal(PrepaymentRules.check(List.of(audience(770)), prepaid(100)));
        assertThat(refusal.ruleCode()).isEqualTo(FeeCodes.PUBLICATION_FUNDS);
        assertThat(refusal.params()).containsEntry("total", "770").containsEntry("available", "100")
            .containsEntry("shortfall", "670").containsEntry("billingMode", FeeRules.PREPAID)
            .containsEntry(QbPrepayments.AUDIENCE, "770");
    }

    @Test
    void aNegativeItemIsADefect() {
        assertThatThrownBy(() -> audience(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
