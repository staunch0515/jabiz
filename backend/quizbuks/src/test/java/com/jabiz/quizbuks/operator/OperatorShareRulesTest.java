package com.jabiz.quizbuks.operator;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** F-M12: the rounding and the cases of the operators' share of the platform fees (docs/quizbuks/plans/impl/F-M12.md). */
class OperatorShareRulesTest {

    private static BigDecimal n(String value) {
        return new BigDecimal(value);
    }

    private static OperatorShareRules.Terms terms(Set<String> kinds, boolean aiCost, boolean outside) {
        return new OperatorShareRules.Terms(kinds, aiCost, outside, BigDecimal.ONE, Map.of(
            "ONBOARDING", OperatorShareRules.OPERATOR, "MANUAL", OperatorShareRules.PLATFORM));
    }

    private static final Set<String> DEFAULT_KINDS = Set.copyOf(OperatorShareRules.DEFAULT_KINDS);

    // ---- the base -------------------------------------------------------------------------------------------------

    @Test
    void aKindOutsideTheListIsWrittenWithAllOfItExcluded() {
        OperatorShareRules.Terms terms = terms(DEFAULT_KINDS, false, false);
        assertThat(OperatorShareRules.excluded("MONTHLY", n("1000"), n("0"), terms)).isEqualByComparingTo("0");
        // The default list has no AI fee: all of it is outside, however its cost part was stated.
        assertThat(OperatorShareRules.excluded("AI", n("1500"), n("1000"), terms)).isEqualByComparingTo("1500");
        assertThat(OperatorShareRules.excluded("EXPOSURE", n("800"), null, terms)).isEqualByComparingTo("0");
    }

    @Test
    void theCostPartIsTakenOffOfAKindInTheListAndNeverMoreThanTheFee() {
        OperatorShareRules.Terms terms = terms(Set.of("AI", "MONTHLY"), false, false);
        assertThat(OperatorShareRules.excluded("AI", n("1500"), n("1000"), terms)).isEqualByComparingTo("1000");
        assertThat(OperatorShareRules.excluded("AI", n("1500"), n("9999"), terms)).isEqualByComparingTo("1500");
        assertThat(OperatorShareRules.excluded("AI", n("1500"), n("-5"), terms)).isEqualByComparingTo("0");
        // The cost part enters the base too when the parameter says so (an AI fee only).
        OperatorShareRules.Terms full = terms(Set.of("AI", "MONTHLY"), true, false);
        assertThat(OperatorShareRules.excluded("AI", n("1500"), n("1000"), full)).isEqualByComparingTo("0");
        assertThat(OperatorShareRules.excluded("MONTHLY", n("1500"), n("1000"), full)).isEqualByComparingTo("1000");
    }

    @Test
    void creditUsedOnAFeeOutsideTheBaseIsTakenBackOnlyWhenTheParameterSaysSoAndOnlyForTheMarkUp() {
        OperatorShareRules.Terms off = terms(DEFAULT_KINDS, false, false);
        OperatorShareRules.Terms on = terms(DEFAULT_KINDS, false, true);
        assertThat(OperatorShareRules.recoverBase("AI", n("1500"), n("1000"), off)).isEqualByComparingTo("0");
        assertThat(OperatorShareRules.recoverBase("AI", n("1500"), n("1000"), on)).isEqualByComparingTo("500");
        // A kind in the list: the part in the base, whatever the parameter.
        assertThat(OperatorShareRules.recoverBase("DISTRIBUTION", n("1000"), n("0"), off)).isEqualByComparingTo("1000");
        assertThat(OperatorShareRules.recoverBase("MONTHLY", n("1000"), n("300"), on)).isEqualByComparingTo("700");
    }

    @Test
    void theKindsAreReadFromTheParameterWithoutBlanksAndRepeats() {
        assertThat(OperatorShareRules.parseKinds(" monthly, MANUAL ,,monthly,audience")).containsExactly("MONTHLY",
            "MANUAL", "AUDIENCE");
        assertThat(OperatorShareRules.parseKinds(null)).isEmpty();
        assertThat(OperatorShareRules.parseKinds("")).isEmpty();
    }

    @Test
    void aSourceTheTermsHaveNoWordOnIsBorneByThePlatformAndThereIsNoThirdTreatment() {
        OperatorShareRules.Terms terms = terms(DEFAULT_KINDS, false, false);
        assertThat(terms.treatmentOf("ONBOARDING")).isEqualTo(OperatorShareRules.OPERATOR);
        assertThat(terms.treatmentOf("MANUAL")).isEqualTo(OperatorShareRules.PLATFORM);
        assertThat(terms.treatmentOf("LIGHT")).isEqualTo(OperatorShareRules.PLATFORM);
        assertThat(terms.treatmentOf(null)).isEqualTo(OperatorShareRules.PLATFORM);
        assertThat(OperatorShareRules.TREATMENTS).containsExactly("PLATFORM", "OPERATOR");
        assertThat(OperatorShareRules.treatment("BOTH")).isFalse();
    }

    // ---- cutting a payment ----------------------------------------------------------------------------------------

    @Test
    void ratiosAreRoundedTowardNegative() {
        assertThat(OperatorShareRules.ratioFloor(n("100"), n("1000"), n("1100"))).isEqualByComparingTo("90");
        assertThat(OperatorShareRules.ratioFloor(n("-100"), n("1000"), n("1100"))).isEqualByComparingTo("-91");
        assertThat(OperatorShareRules.ratioFloor(n("5"), n("1"), n("0"))).isEqualByComparingTo("0");
    }

    @Test
    void anInvoicesCreditIsCutByTheLargestRemainderAndAddsUpExactly() {
        // 100 yen over three equal fees: 34 33 33, the extra yen to the first by id (equal remainders).
        List<BigDecimal> equal = OperatorShareRules.split(n("100"), List.of(n("1100"), n("1100"), n("1100")),
            List.of("c-2", "c-1", "c-3"));
        assertThat(equal).extracting(BigDecimal::intValueExact).containsExactly(33, 34, 33);
        // Unequal weights: 1100 over 770 and 330 is 770 and 330; 1 yen over them goes to the larger remainder.
        assertThat(OperatorShareRules.split(n("1100"), List.of(n("770"), n("330")), List.of("a", "b")))
            .extracting(BigDecimal::intValueExact).containsExactly(770, 330);
        // 10 over 1, 1, 1 (3.33 each) leaves one yen; 7 over 5 and 3 is 4.375 and 2.625: 4 + 2, then the larger
        // remainder (.625) takes the yen left.
        assertThat(OperatorShareRules.split(n("7"), List.of(n("5"), n("3")), List.of("a", "b")))
            .extracting(BigDecimal::intValueExact).containsExactly(4, 3);
    }

    @Test
    void aCreditGivenBackIsCutTheSameWayWithTheSign() {
        List<BigDecimal> back = OperatorShareRules.split(n("-100"), List.of(n("1100"), n("1100"), n("1100")),
            List.of("c-1", "c-2", "c-3"));
        assertThat(back).extracting(BigDecimal::intValueExact).containsExactly(-34, -33, -33);
        assertThat(back.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("-100");
    }

    @Test
    void splitRefusesWeightsThatAreNotPositive() {
        assertThatThrownBy(() -> OperatorShareRules.split(n("5"), List.of(n("0")), List.of("a")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(OperatorShareRules.split(n("5"), List.of(), List.of())).isEmpty();
    }

    // ---- credit used on one fee, row by row ------------------------------------------------------------------------

    private static OperatorShareRules.ChargeGifts fee(String amount, String total, String inBase, String recover) {
        return new OperatorShareRules.ChargeGifts(n(amount), n(total), n(inBase), n(recover), n("0"), n("0"), n("0"),
            n("0"), n("0"));
    }

    @Test
    void aFeePaidInFullByCreditGivesExactlyItsAmountBeforeTaxAndTheTaxInclusiveCreditToTakeBack() {
        OperatorShareRules.Gift gift = fee("1000", "1100", "1000", "1000").next(n("1100"), true);
        assertThat(gift.amount()).isEqualByComparingTo("1000");
        assertThat(gift.inBase()).isEqualByComparingTo("1000");
        assertThat(gift.recoverGross()).isEqualByComparingTo("1100");
    }

    @Test
    void rowsAddUpToTheCumulativeFigureWithoutAFewYenLostToEachRow() {
        OperatorShareRules.ChargeGifts gifts = fee("1000", "1100", "1000", "1000");
        List<Integer> amounts = new ArrayList<>();
        int sum = 0;
        int recovered = 0;
        for (int i = 0; i < 11; i++) {
            OperatorShareRules.Gift gift = gifts.next(n("100"), true);
            amounts.add(gift.amount().intValueExact());
            sum += gift.amount().intValueExact();
            recovered += gift.recoverGross().intValueExact();
        }
        // ⌊100k ÷ 1.1⌋ less the rows before: 90, 91, 91, 91, 91, 91, 91, 91, 91, 91, 91.
        assertThat(amounts).startsWith(90, 91, 91);
        assertThat(sum).isEqualTo(1000);
        assertThat(recovered).isEqualTo(1100);
    }

    @Test
    void onlyThePartInTheBaseIsNotSharedAndOnlyItsProportionIsTakenBack() {
        // An AI fee of 1500 (1650 with tax) of which 1000 is cost: 500 is in the base. 3,000 of credit cannot pay more
        // than the fee; 1650 pays all of it.
        OperatorShareRules.Gift gift = fee("1500", "1650", "500", "500").next(n("1650"), true);
        assertThat(gift.amount()).isEqualByComparingTo("1500");
        assertThat(gift.inBase()).isEqualByComparingTo("500");
        assertThat(gift.recoverGross()).isEqualByComparingTo("550");
        // Half of it: 750 of the fee, 250 of it in the base, 275 taken back.
        OperatorShareRules.Gift half = fee("1500", "1650", "500", "500").next(n("825"), true);
        assertThat(half.amount()).isEqualByComparingTo("750");
        assertThat(half.inBase()).isEqualByComparingTo("250");
        assertThat(half.recoverGross()).isEqualByComparingTo("275");
    }

    @Test
    void aFeeOutsideTheBaseHasNothingInTheBaseToNotShareOrToTakeBack() {
        OperatorShareRules.Gift gift = fee("1500", "1650", "0", "0").next(n("1650"), true);
        assertThat(gift.amount()).isEqualByComparingTo("1500");
        assertThat(gift.inBase()).isEqualByComparingTo("0");
        assertThat(gift.recoverGross()).isEqualByComparingTo("0");
    }

    @Test
    void creditTheOperatorDoesNotBearIsNotTakenBackAndDoesNotCountInTheProportionOfWhatIs() {
        OperatorShareRules.ChargeGifts gifts = fee("1000", "1100", "1000", "1000");
        // 550 of the credit is the platform's, 550 the operator's: only the second is taken back (⌊550 × 1000 ÷ 1000⌋).
        OperatorShareRules.Gift platform = gifts.next(n("550"), false);
        OperatorShareRules.Gift operator = gifts.next(n("550"), true);
        assertThat(platform.recoverGross()).isEqualByComparingTo("0");
        assertThat(operator.recoverGross()).isEqualByComparingTo("550");
        assertThat(platform.amount().add(operator.amount())).isEqualByComparingTo("1000");
    }

    @Test
    void creditGivenBackReversesWhatWasUsedRowByRow() {
        OperatorShareRules.ChargeGifts gifts = fee("1000", "1100", "1000", "1000");
        OperatorShareRules.Gift used = gifts.next(n("1100"), true);
        OperatorShareRules.Gift back = gifts.next(n("-1100"), true);
        assertThat(back.amount()).isEqualByComparingTo(used.amount().negate());
        assertThat(back.inBase()).isEqualByComparingTo(used.inBase().negate());
        assertThat(back.recoverGross()).isEqualByComparingTo(used.recoverGross().negate());
    }

    @Test
    void rowsAlreadyWrittenCountTowardTheCumulativeFigure() {
        // 550 was used already in an earlier settlement: amount 500, in base 500, taken back 550.
        OperatorShareRules.ChargeGifts gifts = new OperatorShareRules.ChargeGifts(n("1000"), n("1100"), n("1000"),
            n("1000"), n("550"), n("550"), n("500"), n("500"), n("550"));
        OperatorShareRules.Gift rest = gifts.next(n("550"), true);
        assertThat(rest.amount()).isEqualByComparingTo("500");
        assertThat(rest.recoverGross()).isEqualByComparingTo("550");
    }

    // ---- what the operator keeps and gives back --------------------------------------------------------------------

    @Test
    void theShareOfAnAmountIsExactAndRoundedDownOnceOverASum() {
        BigDecimal one = OperatorShareRules.exactShare(n("1"), n("0.3"));
        BigDecimal two = OperatorShareRules.exactShare(n("1"), n("0.3"));
        // 0.7 + 0.7 = 1.4: one yen, not the 0 that rounding each would give.
        assertThat(OperatorShareRules.floor(one.add(two))).isEqualByComparingTo("1");
        assertThat(OperatorShareRules.floor(OperatorShareRules.exactShare(n("1000"), n("0.3"))))
            .isEqualByComparingTo("700");
    }

    @Test
    void whatIsDueIsRoundedTowardNegativeSoItTakesLessAndGivesBackMore() {
        assertThat(OperatorShareRules.due(n("550.5"))).isEqualByComparingTo("550");
        assertThat(OperatorShareRules.due(n("-550.5"))).isEqualByComparingTo("-551");
        assertThat(OperatorShareRules.due(n("0"))).isEqualByComparingTo("0");
    }

    @Test
    void whatIsDueIsTakenFromWhatIsLeftAndTheRestIsCarried() {
        // Nothing carried in, enough left: all taken.
        OperatorShareRules.Recovery all = OperatorShareRules.recover(n("1000"), n("0"), n("1500"), n("0"));
        assertThat(all.recovered()).isEqualByComparingTo("1000");
        assertThat(all.carriedOut()).isEqualByComparingTo("0");
        // 400 left (the example of 3,000 due against 1,500 left, scaled): 400 taken, the rest carried.
        OperatorShareRules.Recovery part = OperatorShareRules.recover(n("1000"), n("200"), n("400"), n("0"));
        assertThat(part.recovered()).isEqualByComparingTo("400");
        assertThat(part.carriedOut()).isEqualByComparingTo("800");
        // Exactly enough.
        OperatorShareRules.Recovery exact = OperatorShareRules.recover(n("300"), n("100"), n("400"), n("0"));
        assertThat(exact.recovered()).isEqualByComparingTo("400");
        assertThat(exact.carriedOut()).isEqualByComparingTo("0");
        // Nothing left: all carried, never a negative payment.
        OperatorShareRules.Recovery none = OperatorShareRules.recover(n("300"), n("0"), n("0"), n("0"));
        assertThat(none.recovered()).isEqualByComparingTo("0");
        assertThat(none.carriedOut()).isEqualByComparingTo("300");
    }

    @Test
    void moreGivenBackThanUsedIsPaidBackToTheOperatorAndNotCarried() {
        OperatorShareRules.Recovery back = OperatorShareRules.recover(n("-700"), n("0"), n("0"), n("0"));
        assertThat(back.recovered()).isEqualByComparingTo("-700");
        assertThat(back.carriedOut()).isEqualByComparingTo("0");
        // What was carried in is set against it first: 300 carried in, 1000 given back: 700 back.
        OperatorShareRules.Recovery net = OperatorShareRules.recover(n("-1000"), n("300"), n("50"), n("0"));
        assertThat(net.recovered()).isEqualByComparingTo("-700");
        assertThat(net.carriedOut()).isEqualByComparingTo("0");
    }

    @Test
    void theExampleOfBorneByTheOperatorCarriesAndThenGivesBackToZero() {
        // Fee 1000 paid by credit (borne by the operator, plan share 0.3): share 700, taken back 1100 -> carried 400.
        BigDecimal share = OperatorShareRules.floor(OperatorShareRules.exactShare(n("1000"), n("0.3")));
        OperatorShareRules.Recovery month = OperatorShareRules.recover(n("1100"), n("0"), share, n("0"));
        assertThat(share).isEqualByComparingTo("700");
        assertThat(month.recovered()).isEqualByComparingTo("700");
        assertThat(month.carriedOut()).isEqualByComparingTo("400");
        // The credit is given back the next month: -1100 due, 400 carried in: 700 paid back, nothing carried.
        OperatorShareRules.Recovery back = OperatorShareRules.recover(n("-1100"), month.carriedOut(), n("0"), n("0"));
        assertThat(back.recovered()).isEqualByComparingTo("-700");
        assertThat(back.carriedOut()).isEqualByComparingTo("0");
        // Altogether the operator was taken 700 and paid back 700.
        assertThat(month.recovered().add(back.recovered())).isEqualByComparingTo("0");
    }

    @Test
    void aRefundFirstPaysTheCostCarriedAndOnlyWhatIsLeftIsPayable() {
        // 500 given back, 500 of cost carried: all of it pays the cost, nothing is paid out.
        OperatorShareRules.Recovery all = OperatorShareRules.recover(n("-500"), n("0"), n("0"), n("500"));
        assertThat(all.recovered()).isEqualByComparingTo("-500");
        assertThat(all.costOffset()).isEqualByComparingTo("500");
        assertThat(all.carriedOut()).isEqualByComparingTo("0");
        // 500 given back, 200 of cost carried: 200 pays it, 300 is paid out (500 - 200).
        OperatorShareRules.Recovery some = OperatorShareRules.recover(n("-500"), n("0"), n("0"), n("200"));
        assertThat(some.costOffset()).isEqualByComparingTo("200");
        // More cost than refund: the refund is all spent on it.
        OperatorShareRules.Recovery more = OperatorShareRules.recover(n("-500"), n("0"), n("0"), n("800"));
        assertThat(more.costOffset()).isEqualByComparingTo("500");
        // A take-back (not a refund) leaves the cost alone.
        OperatorShareRules.Recovery taken = OperatorShareRules.recover(n("300"), n("0"), n("1000"), n("800"));
        assertThat(taken.costOffset()).isEqualByComparingTo("0");
    }

    @Test
    void aNegativeBaseIsAPositiveCostOfTheOperatorsShareOfItRoundedDown() {
        assertThat(OperatorShareRules.negativeBaseCost(n("-1000"), n("0.3"))).isEqualByComparingTo("700");
        assertThat(OperatorShareRules.negativeBaseCost(n("-1"), n("0.3"))).isEqualByComparingTo("0");
        assertThat(OperatorShareRules.negativeBaseCost(n("0"), n("0.3"))).isEqualByComparingTo("0");
        assertThat(OperatorShareRules.negativeBaseCost(n("500"), n("0.3"))).isEqualByComparingTo("0");
    }

    @Test
    void aNoteTakesBackOnlyThePartOfItsFeeThatEnteredTheBase() {
        // A fee of 1,500 of which 1,000 was kept out: a note of 1,500 has 500 in the base; of 750, 250.
        assertThat(OperatorShareRules.noteInBase(n("1500"), n("1500"), n("1000"))).isEqualByComparingTo("500");
        assertThat(OperatorShareRules.noteInBase(n("750"), n("1500"), n("1000"))).isEqualByComparingTo("250");
        // A fee wholly outside the base (a kind outside the list): nothing.
        assertThat(OperatorShareRules.noteInBase(n("1000"), n("1000"), n("1000"))).isEqualByComparingTo("0");
        assertThat(OperatorShareRules.noteInBase(n("400"), n("1000"), n("0"))).isEqualByComparingTo("400");
    }

    @Test
    void aKindsListIsValidOnlyWithNamesOfLettersAndUnderscores() {
        assertThat(OperatorShareRules.validKinds("MONTHLY,MANUAL, AUDIENCE")).isTrue();
        assertThat(OperatorShareRules.validKinds("MONTHLY;MANUAL")).isFalse();
        assertThat(OperatorShareRules.validKinds(" , ")).isFalse();
        assertThat(OperatorShareRules.validKinds("")).isFalse();
        assertThat(OperatorShareRules.validKinds(null)).isFalse();
        assertThat(OperatorShareRules.validKinds("MONTHLY,")).isFalse();
    }

    @Test
    void termsKeptWithACappedSettlementReadBackTheSame() {
        OperatorShareRules.Terms terms = new OperatorShareRules.Terms(java.util.Set.of("MONTHLY", "MANUAL", "AI"), true,
            false, new BigDecimal("0.5000"), java.util.Map.of("ONBOARDING", "OPERATOR", "MANUAL", "PLATFORM"));
        OperatorShareRules.Terms back = OperatorShareRules.Terms.fromText(terms.toText());
        assertThat(back).isEqualTo(terms);
        assertThat(back.treatmentOf("ONBOARDING")).isEqualTo(OperatorShareRules.OPERATOR);
        // No treatments, one kind.
        OperatorShareRules.Terms bare = new OperatorShareRules.Terms(java.util.Set.of("MONTHLY"), false, true,
            BigDecimal.ONE, java.util.Map.of());
        assertThat(OperatorShareRules.Terms.fromText(bare.toText())).isEqualTo(bare);
    }
}
