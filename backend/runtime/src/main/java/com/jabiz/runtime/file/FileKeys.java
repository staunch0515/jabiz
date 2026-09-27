package com.jabiz.runtime.file;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage keys of files (docs/design/14-files.md section 2): {@code <yyyy>/<mm>/<fileId>/<variant>}, derived from the
 * {@code fileId} alone. Year and month are those of the UUIDv7 timestamp, which the id generator takes from the
 * injected clock; nothing about the path is stored in the database.
 */
public final class FileKeys {

    /** Variant name of the (processed) original. */
    public static final String ORIGINAL = "original";

    /** Variant names: {@value #ORIGINAL} or {@code w<width>}. */
    public static final Pattern VARIANT = Pattern.compile("original|w[1-9][0-9]{0,3}");

    private static final Pattern KEY = Pattern.compile(
        "([0-9]{4})/([0-9]{2})/([0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})/("
            + VARIANT.pattern() + ")");

    private FileKeys() {}

    /** The directory of all objects of a file. */
    public static String prefix(UUID fileId) {
        ZonedDateTime time = timeOf(fileId).atZone(ZoneOffset.UTC);
        return String.format("%04d/%02d/%s", time.getYear(), time.getMonthValue(), fileId);
    }

    public static String key(UUID fileId, String variant) {
        if (!VARIANT.matcher(variant).matches()) {
            throw new IllegalArgumentException("Invalid variant name: " + variant);
        }
        return prefix(fileId) + "/" + variant;
    }

    /** The file an object key belongs to; empty for anything that is not a well-formed key of this layout. */
    public static Optional<UUID> fileIdOf(String key) {
        Matcher matcher = KEY.matcher(key);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        UUID id = UUID.fromString(matcher.group(3));
        return key.equals(key(id, matcher.group(4))) ? Optional.of(id) : Optional.empty();
    }

    /** The creation time a UUIDv7 carries (millisecond precision). */
    public static Instant timeOf(UUID fileId) {
        if (fileId.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + fileId);
        }
        return Instant.ofEpochMilli(fileId.getMostSignificantBits() >>> 16);
    }
}
