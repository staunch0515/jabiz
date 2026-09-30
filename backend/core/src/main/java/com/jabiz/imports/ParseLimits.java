package com.jabiz.imports;

/**
 * What a parser may read before refusing the file: a file beyond these limits is refused as a whole, never cut
 * short (docs/design/20-imports.md section 2).
 *
 * @param maxRecords          records (not counting blank ones)
 * @param maxColumns          columns of a record
 * @param maxCellLength       characters of one cell
 * @param maxUncompressedBytes bytes of any one part of a compressed file (XLSX), against decompression bombs
 */
public record ParseLimits(int maxRecords, int maxColumns, int maxCellLength, long maxUncompressedBytes) {

    public static final ParseLimits DEFAULT = new ParseLimits(20_000, 256, 32_768, 256L * 1024 * 1024);

    public ParseLimits {
        if (maxRecords < 1 || maxColumns < 1 || maxCellLength < 1 || maxUncompressedBytes < 1) {
            throw new IllegalArgumentException("Parse limits must be positive");
        }
    }

    public ParseLimits withMaxRecords(int records) {
        return new ParseLimits(records, maxColumns, maxCellLength, maxUncompressedBytes);
    }
}
