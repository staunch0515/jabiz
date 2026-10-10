package com.jabiz.runtime.ledger;

import com.jabiz.ledger.Direction;
import com.jabiz.ledger.LedgerDimension;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Values of dimensions whose source is an identity or reference field (docs/design/11-ledger-events-jobs.md 1.5). */
class CheckPostingTest {

    static final String GIVEN = " 0190F1A2-3B4C-7D5E-8F60-718293A4B5C6";
    static final String CANONICAL = "0190f1a2-3b4c-7d5e-8f60-718293a4b5c6";

    static final List<LedgerDimension> DECLARED = List.of(
        LedgerDimension.define(1, "party", d -> d.entity("Party", "partyId")),
        LedgerDimension.define(2, "channel", d -> d.dictionary("urn:channel")));

    static LedgerProcesses.Line line(Map<String, String> dimensions) {
        return new LedgerProcesses.Line("1100", Direction.DEBIT, BigDecimal.ONE, "memo", dimensions);
    }

    @Test
    void idValuesAreReadInTheFormTheyAreCheckedAndStoredIn() {
        List<LedgerProcesses.Line> lines = List.of(line(Map.of("party", GIVEN, "channel", "Web")),
            line(Map.of("party", "1-2-3-4-5")), line(Map.of()));
        CheckPosting.IdValues ids = CheckPosting.IdValues.of(lines, "party");
        assertThat(ids.canonical()).containsExactly(Map.entry(GIVEN, CANONICAL));
        assertThat(ids.malformed()).containsExactly("1-2-3-4-5");
    }

    @Test
    void idDimensionsAreStoredAsCheckedAndOthersAsGiven() {
        assertThat(CheckPosting.entryFields(line(Map.of("party", GIVEN, "channel", "Web")), DECLARED,
            Map.of("party", Map.of(GIVEN, CANONICAL))))
            .containsEntry("memo", "memo")
            .containsEntry("dimension1", CANONICAL)
            .containsEntry("dimension2", "Web");
    }
}
