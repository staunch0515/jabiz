package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.quizbuks.wallet.BankSettings;
import com.jabiz.runtime.test.TestTokens;
import com.jabiz.quizbuks.wallet.BankTopUps;
import com.jabiz.quizbuks.wallet.WalletCodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q18: before the bank top-up's settings are published (an installation that has not finished its setup), a sponsor
 * asking for a bank top-up is told it is not available yet, never which parameters have no value (platform 16zc). The
 * settings are proposed in one step ({@code QB_BANK_SETTINGS_PROPOSE}, the proposer's own open proposals withdrawn
 * first) and published by another administrator; then the sponsor console reads them.
 */
class BankTopUpSettingsIT extends AccountItSupport {

    @Override
    boolean installs() {
        return false;
    }

    @AfterEach
    void onlyInserted() {
        assertAccountsOnlyInserted();
    }

    @Test
    void withoutTheSettingsASponsorIsToldItIsNotAvailableYet() {
        setup();
        String token = tokenOf(userWithRole(unique("bank"), QbRoles.SPONSOR), QbRoles.SPONSOR);
        assertThat(codes(refused(BankTopUps.START, token, body("amount", 5_000), 422)))
            .contains(WalletCodes.TOPUP_BANK_UNAVAILABLE)
            .doesNotContain("PARAM_NOT_FOUND");
    }

    private Map<String, Object> settings(String bank, long min, long max) {
        return body("bank", bank, "branch", "Shibuya (123)", "accountType", "Ordinary", "accountNumber", "1234567",
            "accountHolder", "QUIZBUKS", "min", min, "max", max, "reason", "Open bank top-ups");
    }

    @Test
    void theSettingsAreProposedTogetherAndPublishedByAnother() {
        setup();
        // The installer proposed the first values (empty account) and nobody published them.
        String proposer = TestTokens.bearer(tokens, "installer", "control.propose");
        String other = TestTokens.bearer(tokens, "other-super", "control.propose");
        assertThat(codes(refused(BankSettings.PROPOSE, other, settings("Mizuho (0001)", 1_000, 500_000), 422)))
            .containsExactly(WalletCodes.BANK_SETTINGS_OPEN);
        assertThat(codes(refused(BankSettings.PROPOSE, proposer, settings("Mizuho (0001)", 5_000, 1_000), 422)))
            .containsExactly(WalletCodes.BANK_SETTINGS_RANGE);

        Map<String, Object> proposed = run(BankSettings.PROPOSE, proposer, settings(" Mizuho (0001) ", 1_000,
            500_000));
        @SuppressWarnings("unchecked")
        List<Object> changeIds = (List<Object>) proposed.get("changeIds");
        assertThat(changeIds).hasSize(7);
        assertThat(proposed.get("keys")).isEqualTo(BankSettings.KEYS);
        // The setup's proposals of these keys are withdrawn; only the new ones are open.
        assertThat(query("SELECT count(*) AS n FROM (SELECT DISTINCT ON (change_id) * FROM sys_control_change_version "
            + "ORDER BY change_id, version_no DESC) c WHERE status = 'PROPOSED' AND change_values LIKE "
            + "'%qb.bank.receiving.bank%'").getFirst().get("n")).isEqualTo(1L);
        // The proposer cannot publish; another administrator does.
        refused("CONTROL_CHANGE_PUBLISH", TestTokens.bearer(tokens, "installer", "control.publish"),
            body("changeId", changeIds.getFirst()), 422);
        publishProposals(Map.of("proposals", changeIds));

        String sponsor = tokenOf(userWithRole(unique("bank"), QbRoles.SPONSOR), QbRoles.SPONSOR);
        assertThat(items("qb.sponsor.bank-account", sponsor, body()).getFirst()).containsEntry("bank", "Mizuho (0001)")
            .containsEntry("accountHolder", "QUIZBUKS").containsEntry("min", 1_000).containsEntry("max", 500_000);
        // Proposing what is in force changes nothing; a changed limit is one proposal.
        assertThat(codes(refused(BankSettings.PROPOSE, proposer, settings("Mizuho (0001)", 1_000, 500_000), 422)))
            .containsExactly(WalletCodes.BANK_SETTINGS_UNCHANGED);
        Map<String, Object> again = run(BankSettings.PROPOSE, proposer, settings("Mizuho (0001)", 2_000, 500_000));
        assertThat(again.get("keys")).isEqualTo(List.of(QbParams.TOPUP_BANK_MIN));
    }
}
