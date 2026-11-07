package com.jabiz.integrity;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks the chain of blocks as stored (docs/design/21-audit-retention.md section 2.3): numbers without gaps from 1,
 * each linked to the hash of the one before, each hash the key's HMAC of the block. Pure: the caller reads the
 * blocks and checks their rows.
 */
public final class SealChain {

    /**
     * A block and the hash stored with it.
     *
     * @param storedHash the hash as stored; the chain is intact when it is the block's {@link SealBlock#hash}
     */
    public record Stored(SealBlock block, String storedHash) {}

    private SealChain() {}

    /**
     * Problems of the chain; blocks in seal order, starting at the first block or at {@code previous}.
     *
     * @param previous the stored block just before {@code blocks} (verification from the middle), or null
     */
    public static List<IntegrityProblem> check(List<Stored> blocks, Stored previous, IntegrityKey key) {
        return check(blocks, previous, IntegrityKeys.of(key));
    }

    /**
     * As {@link #check(List, Stored, IntegrityKey)}, each block verified with the key that signed it when that key is
     * current or retired (decision D78); a block of a key that is neither is {@code OTHER_KEY}.
     */
    public static List<IntegrityProblem> check(List<Stored> blocks, Stored previous, IntegrityKeys keys) {
        List<IntegrityProblem> problems = new ArrayList<>();
        long expectedNo = previous == null ? 1 : previous.block().sealNo() + 1;
        String expectedPrev = previous == null ? SealBlock.GENESIS : previous.storedHash();
        for (Stored stored : blocks) {
            SealBlock block = stored.block();
            if (block.sealNo() != expectedNo) {
                problems.add(new IntegrityProblem(IntegrityProblem.Kind.CHAIN_BROKEN, block.sealNo(), null, null,
                    "Block " + block.sealNo() + " follows block " + (expectedNo - 1)));
            }
            if (!block.prevHash().equals(expectedPrev)) {
                problems.add(new IntegrityProblem(IntegrityProblem.Kind.CHAIN_BROKEN, block.sealNo(), null, null,
                    "Block " + block.sealNo() + " does not link to the block before it"));
            }
            java.util.Optional<IntegrityKey> signer = keys.byId(block.keyId());
            if (signer.isEmpty()) {
                problems.add(new IntegrityProblem(IntegrityProblem.Kind.OTHER_KEY, block.sealNo(), null, null,
                    "Block " + block.sealNo() + " was signed with key " + block.keyId() + ", neither the current key "
                        + keys.current().id() + " nor a previous one"));
            } else if (!block.hash(signer.get()).equals(stored.storedHash())) {
                problems.add(new IntegrityProblem(IntegrityProblem.Kind.CHAIN_BROKEN, block.sealNo(), null, null,
                    "The hash of block " + block.sealNo() + " is not its signature"));
            }
            expectedNo = block.sealNo() + 1;
            expectedPrev = stored.storedHash();
        }
        return List.copyOf(problems);
    }
}
