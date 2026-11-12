package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.fee.DistributionPrices;
import com.jabiz.quizbuks.setup.QbParams;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M6 (docs/quizbuks/plans/impl/F-M.md §9.4; H1 of its review): a challenge ({@code forSponsors}) pays no distribution
 * fee: with the switch on and its operator priced, its submission freezes no price and sets nothing aside, so neither
 * settlement job ever takes it (both take only publications with a frozen price).
 */
class DistributionChallengeIT extends TreeItSupport {

    @Test
    void aChallengeFreezesNoPriceAndSetsNothingAside() {
        Copy copy = copy();
        String direct = sponsorToken(copy.operator().leadId());
        run(DistributionPrices.SET, TestTokens.bearer(tokens, "fm6-challenge-hq", QbPermissions.FEE_MANAGE),
            body("operatorId", copy.operator().operatorId(), "rate", "0.05", "unitFloor", "1", "unitCeiling", "20",
                "minimumDeposit", 500, "reason", "F-M6"));
        setControlledParam(QbParams.DISTRIBUTION_BILLING_ENABLED, "true");
        String publicationId = (String) run("QB_PUBLICATION_SAVE", direct, challenge(direct, copy.treeId(),
            copy.start(), "FM6 challenge")).get("publicationId");
        assertThat(submit(direct, publicationId).get("status")).isIn("LIVE", "SCHEDULED");
        assertThat(query("SELECT for_sponsors FROM qb_publication_card WHERE publication_id = ?::uuid", publicationId)
            .getFirst()).containsEntry("for_sponsors", true);
        assertThat(row(publicationId).get("distribution_unit")).isNull();
        assertThat(query("SELECT 1 FROM qb_prepayment WHERE publication_id = ?", publicationId)).isEmpty();
    }
}
