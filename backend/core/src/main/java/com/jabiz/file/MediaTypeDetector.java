package com.jabiz.file;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;

/**
 * Recognises a file's type from its first bytes (docs/design/14-files.md section 3). What the client declares, the
 * content type and the file name, plays no part. Recognition is strict: content that merely could be a type (a stray
 * MPEG frame sync, an MP4 that may be video) is not recognised, and whatever is not recognised is refused.
 */
public final class MediaTypeDetector {

    /** Bytes the detector looks at; enough for two MPEG audio frames or an Ogg page header. */
    public static final int HEAD_BYTES = 4096;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_SIGNATURE = ascii("%PDF-");
    private static final byte[] OGG_SIGNATURE = ascii("OggS");
    private static final byte[] OPUS_HEAD = ascii("OpusHead");
    private static final byte[] VORBIS_HEAD = {0x01, 'v', 'o', 'r', 'b', 'i', 's'};

    /** Bit rates in kbit/s of MPEG-1 Layer III and of MPEG-2/2.5 Layer III, by the header's index. */
    private static final int[] MPEG1_L3_KBPS = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] MPEG2_L3_KBPS = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] MPEG1_RATES = {44100, 48000, 32000, 0};

    private MediaTypeDetector() {}

    /**
     * The type of a file starting with {@code head} (at most {@link #HEAD_BYTES} are looked at; a shorter array is
     * the whole file), or empty when it is none of {@link MediaTypes}.
     */
    public static Optional<MediaTypes> detect(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        byte[] b = head.length > HEAD_BYTES ? Arrays.copyOf(head, HEAD_BYTES) : head;
        if (b.length >= 3 && u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF) {
            return Optional.of(MediaTypes.JPEG);
        }
        if (startsWith(b, 0, PNG_SIGNATURE)) {
            return Optional.of(MediaTypes.PNG);
        }
        if (startsWith(b, 0, PDF_SIGNATURE)) {
            return Optional.of(MediaTypes.PDF);
        }
        if (isOggAudio(b)) {
            return Optional.of(MediaTypes.OGG);
        }
        if (isM4a(b)) {
            return Optional.of(MediaTypes.M4A);
        }
        if (isMp3(b)) {
            return Optional.of(MediaTypes.MP3);
        }
        return Optional.empty();
    }

    /** An Ogg page whose first packet is an Opus or Vorbis identification header. */
    private static boolean isOggAudio(byte[] b) {
        if (!startsWith(b, 0, OGG_SIGNATURE) || b.length < 27) {
            return false;
        }
        int segments = u(b, 26);
        int payload = 27 + segments;
        return startsWith(b, payload, OPUS_HEAD) || startsWith(b, payload, VORBIS_HEAD);
    }

    /** An ISO base media file whose major brand is an audio-only MPEG-4 brand. */
    private static boolean isM4a(byte[] b) {
        if (b.length < 12 || !startsWith(b, 4, ascii("ftyp"))) {
            return false;
        }
        String brand = new String(b, 8, 4, StandardCharsets.US_ASCII);
        return brand.equals("M4A ") || brand.equals("M4B ");
    }

    /** An ID3v2 tag, or two consecutive valid MPEG audio Layer III frame headers. */
    private static boolean isMp3(byte[] b) {
        if (b.length >= 10 && startsWith(b, 0, ascii("ID3")) && u(b, 3) >= 2 && u(b, 3) <= 4
            && (u(b, 6) | u(b, 7) | u(b, 8) | u(b, 9)) < 0x80) {
            return true;
        }
        int first = frameLength(b, 0);
        return first > 0 && frameLength(b, first) > 0;
    }

    /** The length of the Layer III frame whose header starts at {@code at}; 0 when there is no valid header. */
    static int frameLength(byte[] b, int at) {
        if (at + 4 > b.length || u(b, at) != 0xFF || (u(b, at + 1) & 0xE0) != 0xE0) {
            return 0;
        }
        int version = (u(b, at + 1) >> 3) & 0x03;  // 0: MPEG-2.5, 1: reserved, 2: MPEG-2, 3: MPEG-1
        int layer = (u(b, at + 1) >> 1) & 0x03;    // 1: Layer III
        int bitrateIndex = (u(b, at + 2) >> 4) & 0x0F;
        int rateIndex = (u(b, at + 2) >> 2) & 0x03;
        int padding = (u(b, at + 2) >> 1) & 0x01;
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
            return 0;
        }
        int sampleRate = MPEG1_RATES[rateIndex] / (version == 3 ? 1 : version == 2 ? 2 : 4);
        int bitrate = (version == 3 ? MPEG1_L3_KBPS : MPEG2_L3_KBPS)[bitrateIndex] * 1000;
        int samplesFactor = version == 3 ? 144 : 72;
        return samplesFactor * bitrate / sampleRate + padding;
    }

    private static boolean startsWith(byte[] b, int offset, byte[] prefix) {
        if (offset < 0 || offset + prefix.length > b.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int u(byte[] b, int index) {
        return b[index] & 0xFF;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
