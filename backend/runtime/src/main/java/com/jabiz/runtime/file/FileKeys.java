package com.jabiz.runtime.file;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Storage keys of files (docs/design/14-files.md section 2), derived from the {@code fileId} alone; nothing about the
 * path is stored in the database.
 * <ul>
 *   <li>a UUIDv7 id (the default): {@code <yyyy>/<mm>/<fileId>/<variant>}, the year and month of the id's timestamp,
 *       which the id generator takes from the injected clock;</li>
 *   <li>a UUIDv4 id (a policy with {@code privateMetadata()}, decision D48 item 3: the id reveals neither when nor in
 *       what order the file was uploaded): {@code r/<first two hex digits>/<fileId>/<variant>}.</li>
 * </ul>
 */
public final class FileKeys {

    /** Variant name of the (processed) original. */
    public static final String ORIGINAL = "original";

    /** Variant names: {@value #ORIGINAL} or {@code w<width>}. */
    public static final Pattern VARIANT = Pattern.compile("original|w[1-9][0-9]{0,3}");

    /** The first segment of the keys of files with random (UUIDv4) ids. */
    static final String RANDOM_AREA = "r";

    private static final String UUID7 = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String UUID4 = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";

    private static final Pattern KEY = Pattern.compile("(?:[0-9]{4}/[0-9]{2}/(" + UUID7 + ")|" + RANDOM_AREA
        + "/[0-9a-f]{2}/(" + UUID4 + "))/(" + VARIANT.pattern() + ")");

    private FileKeys() {}

    /** The directory of all objects of a file. */
    public static String prefix(UUID fileId) {
        if (fileId.version() == 4) {
            String id = fileId.toString();
            return RANDOM_AREA + "/" + id.substring(0, 2) + "/" + id;
        }
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
        UUID id = UUID.fromString(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        return key.equals(key(id, matcher.group(3))) ? Optional.of(id) : Optional.empty();
    }

    /** Whether the id carries its creation time (a UUIDv7); a random (UUIDv4) one does not. */
    public static boolean timed(UUID fileId) {
        return fileId.version() == 7;
    }

    /** The creation time a UUIDv7 carries (millisecond precision). */
    public static Instant timeOf(UUID fileId) {
        if (!timed(fileId)) {
            throw new IllegalArgumentException("Not a UUIDv7: " + fileId);
        }
        return Instant.ofEpochMilli(fileId.getMostSignificantBits() >>> 16);
    }
}
