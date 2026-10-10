package com.jabiz.runtime.ledger;

import com.jabiz.ledger.Direction;
import com.jabiz.ledger.LedgerDimension;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Values of dimensions whose source is an identity or reference field (docs/design/11-ledger-events-jobs.md 1.5). */
class CheckPostingTest {

    @Test
    void anIdIsTakenInItsCanonicalFormOnly() {
        assertThat(CheckPosting.canonicalId("0190F1A2-3B4C-7D5E-8F60-718293A4B5C6"))
            .isEqualTo("0190f1a2-3b4c-7d5e-8f60-718293a4b5c6");
        assertThat(CheckPosting.canonicalId(" 0190f1a2-3b4c-7d5e-8f60-718293a4b5c6 ")).isNull();
        // UUID.fromString would take these, but they are not how an id is written.
        assertThat(CheckPosting.canonicalId("1-2-3-4-5")).isNull();
        assertThat(CheckPosting.canonicalId("0190f1a23b4c7d5e8f60718293a4b5c6")).isNull();
        assertThat(CheckPosting.canonicalId("not-a-uuid")).isNull();
        assertThat(CheckPosting.canonicalId("")).isNull();
    }

    @Test
    void idDimensionsAreStoredInLowerCaseAndOthersAsGiven() {
        List<LedgerDimension> declared = List.of(LedgerDimension.define(1, "party", d -> d.entity("Party", "partyId")),
            LedgerDimension.define(2, "channel", d -> d.dictionary("urn:channel")));
        LedgerProcesses.Line line = new LedgerProcesses.Line("1100", Direction.DEBIT, BigDecimal.ONE, "memo",
            Map.of("party", "0190F1A2-3B4C-7D5E-8F60-718293A4B5C6", "channel", "Web"));
        assertThat(CheckPosting.entryFields(line, declared, Set.of("party")))
            .containsEntry("memo", "memo")
            .containsEntry("dimension1", "0190f1a2-3b4c-7d5e-8f60-718293a4b5c6")
            .containsEntry("dimension2", "Web");
    }
}
