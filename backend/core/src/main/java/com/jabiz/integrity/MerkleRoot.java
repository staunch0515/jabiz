package com.jabiz.integrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The Merkle root of a seal's rows (docs/design/21-audit-retention.md section 2): leaves in table and key order, a
 * leaf is SHA-256(0x00, table, 0x00, key, 0x00, digest), a node SHA-256(0x01, left, right); an odd node is carried
 * up unchanged. The prefixes keep a leaf from ever passing for a node. No rows: SHA-256 of nothing.
 */
public final class MerkleRoot {

    private static final byte LEAF = 0;
    private static final byte NODE = 1;

    private MerkleRoot() {}

    /** Hex root of the rows, whatever their order. */
    public static String of(List<SealedRow> rows) {
        List<SealedRow> sorted = new ArrayList<>(rows);
        sorted.sort(null);
        if (sorted.isEmpty()) {
            return HexFormat.of().formatHex(sha256().digest());
        }
        List<byte[]> level = new ArrayList<>(sorted.size());
        for (SealedRow row : sorted) {
            MessageDigest digest = sha256();
            digest.update(LEAF);
            digest.update(row.table().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(row.key().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(row.digest().getBytes(StandardCharsets.UTF_8));
            level.add(digest.digest());
        }
        while (level.size() > 1) {
            List<byte[]> next = new ArrayList<>((level.size() + 1) / 2);
            for (int i = 0; i < level.size(); i += 2) {
                if (i + 1 == level.size()) {
                    next.add(level.get(i));
                } else {
                    MessageDigest digest = sha256();
                    digest.update(NODE);
                    digest.update(level.get(i));
                    digest.update(level.get(i + 1));
                    next.add(digest.digest());
                }
            }
            level = next;
        }
        return HexFormat.of().formatHex(level.getFirst());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
