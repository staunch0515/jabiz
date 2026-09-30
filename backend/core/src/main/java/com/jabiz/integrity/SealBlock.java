package com.jabiz.integrity;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * One block of the seal chain (docs/design/21-audit-retention.md section 2): the rows sealed together, summed up by
 * their Merkle root, linked to the block before by its hash and signed with the integrity key.
 *
 * @param sealNo     1, 2, 3 ... without gaps
 * @param sealedTime when the block was made
 * @param rowCount   how many rows it seals
 * @param merkleRoot {@link MerkleRoot} of its rows
 * @param prevHash   the hash of the block before; {@link #GENESIS} for the first
 * @param keyId      {@link IntegrityKey#id()} of the key that signed it
 */
public record SealBlock(long sealNo, Instant sealedTime, int rowCount, String merkleRoot, String prevHash,
    String keyId) {

    /** The "hash before" the first block. */
    public static final String GENESIS = "0".repeat(64);

    private static final Pattern HEX = Pattern.compile("[0-9a-f]{64}");

    public SealBlock {
        if (sealNo < 1) {
            throw new IllegalArgumentException("sealNo must be positive");
        }
        Objects.requireNonNull(sealedTime, "sealedTime must not be null");
        if (rowCount < 0) {
            throw new IllegalArgumentException("rowCount must not be negative");
        }
        requireHex("merkleRoot", merkleRoot);
        requireHex("prevHash", prevHash);
        Objects.requireNonNull(keyId, "keyId must not be null");
    }

    /** The block's hash: HMAC-SHA256 of its fields, in a fixed layout, with the key. */
    public String hash(IntegrityKey key) {
        return key.sign(String.join("\n", "jabiz-seal-v1", Long.toString(sealNo), sealedTime.toString(),
            Integer.toString(rowCount), merkleRoot, prevHash, keyId));
    }

    private static void requireHex(String name, String value) {
        if (value == null || !HEX.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be 64 lower-case hex characters");
        }
    }
}
