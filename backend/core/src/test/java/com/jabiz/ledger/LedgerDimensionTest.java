package com.jabiz.ledger;

import com.jabiz.entity.Violation;
import com.jabiz.i18n.PlatformErrorCodes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static com.jabiz.ledger.Direction.CREDIT;
import static com.jabiz.ledger.Direction.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Analysis dimensions and memos of ledger entries (docs/design/11-ledger-events-jobs.md section 1.5). */
class LedgerDimensionTest {

    static final List<LedgerDimension> DIMENSIONS = List.of(
        LedgerDimension.define(1, "department", d -> d.dictionary("urn:dept")),
        LedgerDimension.define(3, "location", d -> d.entity("Location", "code")));

    @Test
    void anIdValueIsTakenInItsCanonicalFormOnly() {
        assertThat(LedgerDimension.canonicalId("0190F1A2-3B4C-7D5E-8F60-718293A4B5C6"))
            .isEqualTo("0190f1a2-3b4c-7d5e-8f60-718293a4b5c6");
        // Trimmed as EntityDefinition.normalizeId trims.
        assertThat(LedgerDimension.canonicalId(" 0190f1a2-3b4c-7d5e-8f60-718293a4b5c6 "))
            .isEqualTo("0190f1a2-3b4c-7d5e-8f60-718293a4b5c6");
        // UUID.fromString takes shortened groups, but no stored id is written so.
        assertThat(LedgerDimension.canonicalId("1-2-3-4-5")).isNull();
        assertThat(LedgerDimension.canonicalId("0190f1a23b4c7d5e8f60718293a4b5c6")).isNull();
        assertThat(LedgerDimension.canonicalId("not-a-uuid")).isNull();
        assertThat(LedgerDimension.canonicalId("")).isNull();
        assertThat(LedgerDimension.canonicalId(null)).isNull();
    }

    @Test
    void aDimensionNamesItsColumnAndItsValues() {
        LedgerDimension department = DIMENSIONS.getFirst();
        assertThat(department.field()).isEqualTo("dimension1");
        assertThat(department.source()).isEqualTo(new LedgerDimension.DictionarySource("urn:dept"));
        assertThat(DIMENSIONS.get(1).source()).isEqualTo(new LedgerDimension.EntitySource("Location", "code"));
        assertThatThrownBy(() -> LedgerDimension.define(5, "x", d -> d.dictionary("u")))
            .hasMessageContaining("1 to 4");
        assertThatThrownBy(() -> LedgerDimension.define(0, "x", d -> d.dictionary("u")))
            .hasMessageContaining("1 to 4");
        assertThatThrownBy(() -> LedgerDimension.define(1, "2x", d -> d.dictionary("u")))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> LedgerDimension.define(1, "x", d -> { }))
            .hasMessageContaining("source of values");
    }

    @Test
    void memosAndDimensionsAreCheckedWithTheLines() {
        String longMemo = "m".repeat(PostingLine.MAX_MEMO + 1);
        List<PostingLine> lines = List.of(
            new PostingLine("1000", DEBIT, BigDecimal.TEN, longMemo, Map.of("department", "SALES", "colour", "red")),
            new PostingLine("4000", CREDIT, BigDecimal.TEN, "ok", Map.of("location", " ")));
        List<Violation> violations = LedgerPosting.validate(lines, 0, DIMENSIONS);
        assertThat(violations).extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.TOO_LONG,
            PlatformErrorCodes.LEDGER_DIMENSION_UNKNOWN, PlatformErrorCodes.LEDGER_DIMENSION_INVALID);
        assertThat(violations.get(1).params()).containsEntry("dimension", "colour").containsEntry("line", 1);
        assertThat(violations.get(2).params()).containsEntry("line", 2);

        assertThat(LedgerPosting.validate(List.of(
            new PostingLine("1000", DEBIT, BigDecimal.TEN, "rent", Map.of("department", "SALES")),
            new PostingLine("4000", CREDIT, BigDecimal.TEN)), 0, DIMENSIONS)).isEmpty();
        assertThat(LedgerPosting.validate(List.of(
            new PostingLine("1000", DEBIT, BigDecimal.TEN, null, Map.of("location", "x".repeat(101))),
            new PostingLine("4000", CREDIT, BigDecimal.TEN)), 0, DIMENSIONS))
            .extracting(Violation::ruleCode).containsExactly(PlatformErrorCodes.LEDGER_DIMENSION_INVALID);
    }

    @Test
    void aReversalKeepsMemoAndDimensions() {
        PostingLine line = new PostingLine("1000", DEBIT, BigDecimal.ONE, "rent", Map.of("department", "SALES"));
        assertThat(line.reversed()).isEqualTo(new PostingLine("1000", CREDIT, BigDecimal.ONE, "rent",
            Map.of("department", "SALES")));
        assertThat(new PostingLine("1000", DEBIT, BigDecimal.ONE, null, null).dimensions()).isEmpty();
    }
}
