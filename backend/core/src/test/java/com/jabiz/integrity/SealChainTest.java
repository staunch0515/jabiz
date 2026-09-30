package com.jabiz.integrity;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Merkle root, the key and the chain of seals (docs/design/21-audit-retention.md section 2). */
class SealChainTest {

    private static final IntegrityKey KEY = key("the-integrity-key-of-these-tests-0001");
    private static final IntegrityKey OTHER = key("another-integrity-key-of-these-tests-2");
    private static final Instant T = Instant.parse("2026-01-31T09:00:00Z");

    private static IntegrityKey key(String text) {
        return new IntegrityKey(text.getBytes(StandardCharsets.UTF_8));
    }

    private static SealedRow row(String table, String key, char digit) {
        return new SealedRow(table, key, String.valueOf(digit).repeat(64));
    }

    @Test
    void theRootDependsOnEveryRowButNotOnTheirOrder() {
        List<SealedRow> rows = List.of(row("b", "[1]", 'a'), row("a", "[2]", 'b'), row("a", "[1]", 'c'));
        String root = MerkleRoot.of(rows);
        assertThat(root).hasSize(64).isEqualTo(MerkleRoot.of(List.of(rows.get(2), rows.get(0), rows.get(1))));
        assertThat(MerkleRoot.of(List.of(rows.get(0), rows.get(1), row("a", "[1]", 'd')))).isNotEqualTo(root);
        assertThat(MerkleRoot.of(rows.subList(0, 2))).isNotEqualTo(root);
        assertThat(MerkleRoot.of(List.of(row("a", "[1]", 'c'), row("a", "[1]", 'c')))).isNotEqualTo(
            MerkleRoot.of(List.of(row("a", "[1]", 'c'))));
        // Moving a row to another key or table changes the root although the digests stay.
        assertThat(MerkleRoot.of(List.of(row("a", "[3]", 'c'), rows.get(0), rows.get(1)))).isNotEqualTo(root);
        assertThat(MerkleRoot.of(List.of()))
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(MerkleRoot.of(List.of(rows.get(0)))).isNotEqualTo(rows.get(0).digest());
    }

    @Test
    void theKeyHasAnIdThatRevealsNothingAndMustBeLongEnough() {
        assertThat(KEY.id()).hasSize(16).isNotEqualTo(OTHER.id()).isEqualTo(
            key("the-integrity-key-of-these-tests-0001").id());
        assertThat(KEY.toString()).doesNotContain("the-integrity").contains(KEY.id());
        assertThat(KEY).isEqualTo(key("the-integrity-key-of-these-tests-0001")).isNotEqualTo(OTHER)
            .hasSameHashCodeAs(key("the-integrity-key-of-these-tests-0001"));
        assertThatThrownBy(() -> new IntegrityKey(new byte[31])).hasMessageContaining("32 bytes");
    }

    @Test
    void anIntactChainHasNoProblems() {
        assertThat(SealChain.check(chain(3, KEY), null, KEY)).isEmpty();
        List<SealChain.Stored> blocks = chain(3, KEY);
        assertThat(SealChain.check(blocks.subList(1, 3), blocks.get(0), KEY)).isEmpty();
        assertThat(SealChain.check(List.of(), null, KEY)).isEmpty();
    }

    @Test
    void changedRemovedAndForeignBlocksAreFound() {
        List<SealChain.Stored> blocks = chain(4, KEY);

        // A block whose root was changed and stored hash kept: its signature fails.
        SealBlock b2 = blocks.get(1).block();
        List<SealChain.Stored> changed = new ArrayList<>(blocks);
        changed.set(1, new SealChain.Stored(new SealBlock(2, b2.sealedTime(), b2.rowCount(), "f".repeat(64),
            b2.prevHash(), b2.keyId()), blocks.get(1).storedHash()));
        assertThat(SealChain.check(changed, null, KEY)).extracting(IntegrityProblem::kind, IntegrityProblem::sealNo)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(IntegrityProblem.Kind.CHAIN_BROKEN, 2L));

        // Re-signing the changed block without the key is impossible; with another key the next link breaks.
        SealBlock forged = new SealBlock(2, b2.sealedTime(), b2.rowCount(), "f".repeat(64), b2.prevHash(), KEY.id());
        changed.set(1, new SealChain.Stored(forged, forged.hash(OTHER)));
        assertThat(SealChain.check(changed, null, KEY)).extracting(IntegrityProblem::sealNo).containsExactly(2L, 3L);

        // A removed block: a gap and a broken link.
        List<SealChain.Stored> removed = new ArrayList<>(blocks);
        removed.remove(2);
        assertThat(SealChain.check(removed, null, KEY)).extracting(IntegrityProblem::kind, IntegrityProblem::sealNo)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(IntegrityProblem.Kind.CHAIN_BROKEN, 4L),
                org.assertj.core.groups.Tuple.tuple(IntegrityProblem.Kind.CHAIN_BROKEN, 4L));

        // Blocks signed with another key cannot be checked with this one.
        assertThat(SealChain.check(chain(2, OTHER), null, KEY)).extracting(IntegrityProblem::kind)
            .containsOnly(IntegrityProblem.Kind.OTHER_KEY).hasSize(2);
    }

    @Test
    void blocksAreValidated() {
        assertThatThrownBy(() -> new SealBlock(0, T, 0, SealBlock.GENESIS, SealBlock.GENESIS, "k"))
            .hasMessageContaining("sealNo");
        assertThatThrownBy(() -> new SealBlock(1, T, -1, SealBlock.GENESIS, SealBlock.GENESIS, "k"))
            .hasMessageContaining("rowCount");
        assertThatThrownBy(() -> new SealBlock(1, T, 0, "ABC", SealBlock.GENESIS, "k"))
            .hasMessageContaining("merkleRoot");
        assertThatThrownBy(() -> new SealBlock(1, T, 0, SealBlock.GENESIS, null, "k"))
            .hasMessageContaining("prevHash");
        assertThatThrownBy(() -> new SealedRow("t", null, "d")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new IntegrityProblem(null, 1L, null, null, "x"))
            .isInstanceOf(NullPointerException.class);
    }

    private static List<SealChain.Stored> chain(int length, IntegrityKey key) {
        List<SealChain.Stored> blocks = new ArrayList<>();
        String prev = SealBlock.GENESIS;
        for (int no = 1; no <= length; no++) {
            SealBlock block = new SealBlock(no, T.plusSeconds(300L * no), no,
                MerkleRoot.of(List.of(row("t", "[" + no + "]", 'a'))), prev, key.id());
            String hash = block.hash(key);
            blocks.add(new SealChain.Stored(block, hash));
            prev = hash;
        }
        return blocks;
    }
}
