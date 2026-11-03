package com.jabiz.quizbuks.wallet;

import com.jabiz.entity.Violation;
import com.jabiz.runtime.EntityInstance;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** What may move between a sponsor's cash and Kudos (Q87a, D-Q87-3): the switches and what is available. */
class KudosExchangesTest {

    static EntityInstance wallet(long balance, long held, long pendingCount, long pendingAmount) {
        Map<String, Object> values = new HashMap<>();
        values.put("balance", BigDecimal.valueOf(balance));
        values.put("held", BigDecimal.valueOf(held));
        values.put("pendingCount", BigDecimal.valueOf(pendingCount));
        values.put("pendingAmount", BigDecimal.valueOf(pendingAmount));
        return new EntityInstance("w", QbWallets.WALLET, 1, null, values);
    }

    static List<String> codes(List<Violation> problems) {
        return problems.stream().map(Violation::ruleCode).toList();
    }

    @Test
    void buyingTakesWhatIsAvailableOfTheCash() {
        EntityInstance cash = wallet(5000, 1000, 0, 0);
        assertThat(KudosExchanges.problems(true, true, BigDecimal.valueOf(4000), null, cash)).isEmpty();
        // What an open dispute holds is not available.
        assertThat(codes(KudosExchanges.problems(true, true, BigDecimal.valueOf(4001), null, cash)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);
        // No cash account yet: nothing available.
        assertThat(codes(KudosExchanges.problems(true, true, BigDecimal.ONE, null, null)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);
        // Rewards waiting for Kudos do not stop buying them (the new Kudos pay them).
        assertThat(KudosExchanges.problems(true, true, BigDecimal.TEN, wallet(0, 0, 2, 300), cash)).isEmpty();
    }

    @Test
    void sellingNeverOvertakesRewardsWaitingForTheKudos() {
        EntityInstance kudos = wallet(3000, 0, 0, 0);
        assertThat(KudosExchanges.problems(false, true, BigDecimal.valueOf(3000), kudos, null)).isEmpty();
        assertThat(codes(KudosExchanges.problems(false, true, BigDecimal.valueOf(3001), kudos, null)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_FUNDS);
        assertThat(codes(KudosExchanges.problems(false, true, BigDecimal.ONE, wallet(3000, 0, 1, 100), null)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_PENDING);
    }

    @Test
    void aSwitchThatIsOffOrNotSetRefusesEverything() {
        EntityInstance cash = wallet(5000, 0, 0, 0);
        assertThat(codes(KudosExchanges.problems(true, false, BigDecimal.ONE, null, cash)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
        assertThat(codes(KudosExchanges.problems(false, null, BigDecimal.ONE, cash, null)))
            .containsExactly(WalletCodes.KUDOS_EXCHANGE_OFF);
    }
}
