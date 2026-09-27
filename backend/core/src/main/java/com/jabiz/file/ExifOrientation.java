package com.jabiz.file;

/**
 * Reads the orientation tag (0x0112) of a JPEG's EXIF block, the only piece of metadata the platform uses before
 * discarding all of it (docs/design/14-files.md section 3). The JDK does not parse EXIF; this reads just enough of the
 * APP1 segment and its TIFF structure, and treats anything malformed as "no orientation".
 */
public final class ExifOrientation {

    /** The image is stored upright. */
    public static final int NORMAL = 1;

    private static final int ORIENTATION_TAG = 0x0112;
    private static final int SHORT_TYPE = 3;

    private ExifOrientation() {}

    /**
     * The orientation, 1 to 8, of the JPEG starting with {@code head} (the first segments are enough, the image
     * data is not needed); {@link #NORMAL} when there is none or it cannot be read.
     */
    public static int of(byte[] head) {
        if (head == null || head.length < 4 || u8(head, 0) != 0xFF || u8(head, 1) != 0xD8) {
            return NORMAL;
        }
        int at = 2;
        while (at + 4 <= head.length) {
            if (u8(head, at) != 0xFF) {
                return NORMAL;
            }
            int marker = u8(head, at + 1);
            if (marker == 0xD8 || (marker >= 0xD0 && marker <= 0xD7) || marker == 0x01 || marker == 0xFF) {
                at += marker == 0xFF ? 1 : 2;
                continue;
            }
            if (marker == 0xDA || marker == 0xD9) {
                return NORMAL; // image data starts: no EXIF before it
            }
            int length = u16(head, at + 2, false);
            if (length < 2) {
                return NORMAL;
            }
            int data = at + 4;
            if (marker == 0xE1 && data + 6 <= head.length && isExifHeader(head, data)) {
                return fromTiff(head, data + 6, Math.min(head.length, at + 2 + length));
            }
            at += 2 + length;
        }
        return NORMAL;
    }

    private static boolean isExifHeader(byte[] b, int at) {
        return b[at] == 'E' && b[at + 1] == 'x' && b[at + 2] == 'i' && b[at + 3] == 'f' && b[at + 4] == 0
            && b[at + 5] == 0;
    }

    private static int fromTiff(byte[] b, int tiff, int end) {
        if (tiff + 8 > end) {
            return NORMAL;
        }
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return NORMAL;
        }
        if (u16(b, tiff + 2, little) != 42) {
            return NORMAL;
        }
        long ifdOffset = u32(b, tiff + 4, little);
        if (ifdOffset < 8 || tiff + ifdOffset + 2 > end) {
            return NORMAL;
        }
        int ifd = (int) (tiff + ifdOffset);
        int entries = u16(b, ifd, little);
        for (int i = 0; i < entries; i++) {
            int entry = ifd + 2 + i * 12;
            if (entry + 12 > end) {
                return NORMAL;
            }
            if (u16(b, entry, little) == ORIENTATION_TAG) {
                if (u16(b, entry + 2, little) != SHORT_TYPE || u32(b, entry + 4, little) != 1) {
                    return NORMAL;
                }
                int value = u16(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : NORMAL;
            }
        }
        return NORMAL;
    }

    private static int u8(byte[] b, int at) {
        return b[at] & 0xFF;
    }

    private static int u16(byte[] b, int at, boolean little) {
        return little ? u8(b, at) | u8(b, at + 1) << 8 : u8(b, at) << 8 | u8(b, at + 1);
    }

    private static long u32(byte[] b, int at, boolean little) {
        long value = little
            ? u8(b, at) | u8(b, at + 1) << 8 | u8(b, at + 2) << 16 | (long) u8(b, at + 3) << 24
            : (long) u8(b, at) << 24 | u8(b, at + 1) << 16 | u8(b, at + 2) << 8 | u8(b, at + 3);
        return value & 0xFFFF_FFFFL;
    }
}
