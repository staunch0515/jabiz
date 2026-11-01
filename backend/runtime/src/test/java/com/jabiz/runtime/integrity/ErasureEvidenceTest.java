package com.jabiz.runtime.integrity;

import com.jabiz.integrity.IntegrityKey;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ErasureEvidenceTest {

    private static final IntegrityKey KEY = new IntegrityKey("k".repeat(32).getBytes(StandardCharsets.UTF_8));
    private static final UUID ERASURE = UUID.fromString("0190a1b2-0000-7000-8000-000000000001");
    private static final ErasureEvidence.Item A = new ErasureEvidence.Item("sec_user_version", "[1]",
        List.of("email"), "a".repeat(64), "b".repeat(64));
    private static final ErasureEvidence.Item B = new ErasureEvidence.Item("op_process", "[7]",
        List.of("input_summary"), null, "c".repeat(64));

    @Test
    void theOrderOfTheEntriesDoesNotMatter() {
        assertThat(ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A, B)))
            .isEqualTo(ErasureEvidence.sign(KEY, ERASURE, 2, List.of(B, A)));
    }

    @Test
    void twoEntriesOfOneRowAreSignedInAnOrderOfTheirOwn() {
        // One row written twice by a purge: the database may return its entries either way round.
        ErasureEvidence.Item first = new ErasureEvidence.Item("qb_invitation", "[i-1]", List.of("email"),
            "a".repeat(64), "b".repeat(64));
        ErasureEvidence.Item second = new ErasureEvidence.Item("qb_invitation", "[i-1]", List.of("note"),
            "b".repeat(64), "c".repeat(64));
        assertThat(ErasureEvidence.sign(KEY, ERASURE, 2, List.of(first, second, B)))
            .isEqualTo(ErasureEvidence.sign(KEY, ERASURE, 2, List.of(second, B, first)));
    }

    @Test
    void everyPartIsSigned() {
        String signed = ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A, B));
        assertThat(List.of(
            ErasureEvidence.sign(KEY, UUID.fromString("0190a1b2-0000-7000-8000-000000000002"), 2, List.of(A, B)),
            ErasureEvidence.sign(KEY, ERASURE, 3, List.of(A, B)),
            ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A)),
            ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A, new ErasureEvidence.Item(B.table(), B.rowKey(),
                B.columns(), null, "d".repeat(64)))),
            ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A, new ErasureEvidence.Item(B.table(), B.rowKey(),
                B.columns(), "", B.after()))),
            ErasureEvidence.sign(KEY, ERASURE, 2, List.of(A, new ErasureEvidence.Item(B.table(), B.rowKey(),
                List.of("input_summary", "x"), B.before(), B.after()))),
            ErasureEvidence.sign(new IntegrityKey("j".repeat(32).getBytes(StandardCharsets.UTF_8)), ERASURE, 2,
                List.of(A, B))))
            .doesNotContain(signed);
    }

    @Test
    void noValueRunsIntoTheNext() {
        // The same characters split differently between the table and the key are another text.
        ErasureEvidence.Item split = new ErasureEvidence.Item("ab", "c", List.of(), null, "e");
        ErasureEvidence.Item other = new ErasureEvidence.Item("a", "bc", List.of(), null, "e");
        assertThat(ErasureEvidence.text(ERASURE, 1, List.of(split)))
            .isNotEqualTo(ErasureEvidence.text(ERASURE, 1, List.of(other)));
    }
}
