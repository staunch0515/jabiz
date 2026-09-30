package com.jabiz.integrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The columns a block's rows were digested over, table by table (docs/design/21-audit-retention.md section 2.1). A
 * row's digest covers the columns its table had when it was sealed: a column added by a later migration does not
 * change it, while a sealed column changed, dropped or renamed does.
 */
public final class SealedColumns {

    private SealedColumns() {}

    /** SHA-256 of one line per table, in table order: the table, a tab, the columns joined by commas. */
    public static String hash(Map<String, List<String>> columnsByTable) {
        MessageDigest digest = MerkleRoot.sha256();
        new TreeMap<>(columnsByTable).forEach((table, columns) -> digest.update(
            (table + "\t" + String.join(",", columns) + "\n").getBytes(StandardCharsets.UTF_8)));
        return HexFormat.of().formatHex(digest.digest());
    }
}
