package com.jabiz.file;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Recognises a file's type from its first bytes (docs/design/14-files.md section 3). What the client declares, the
 * content type and the file name, plays no part. Recognition is strict: content that merely could be a type (a stray
 * MPEG frame sync, an MP4 that may be video) is not recognised, and whatever is not recognised is refused.
 *
 * <p>The import types (decision D26): XLSX is a ZIP archive with a workbook and the content type of a macro-free
 * workbook, which needs the whole file ({@link #detect(Path)}); XML starts with an XML declaration or an element that
 * is not HTML or SVG; text has no NUL and no control character other than tab, line feed, form feed and carriage
 * return in its first bytes.
 */
public final class MediaTypeDetector {

    /** Bytes the detector looks at; enough for two MPEG audio frames or an Ogg page header. */
    public static final int HEAD_BYTES = 4096;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] PDF_SIGNATURE = ascii("%PDF-");
    private static final byte[] OGG_SIGNATURE = ascii("OggS");
    private static final byte[] OPUS_HEAD = ascii("OpusHead");
    private static final byte[] VORBIS_HEAD = {0x01, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final byte[] ZIP_SIGNATURE = {'P', 'K', 3, 4};
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    /** The content type part a macro-free workbook declares; a macro-enabled one declares another. */
    private static final String WORKBOOK_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml."
        + "sheet.main+xml";
    /** Most bytes of {@code [Content_Types].xml} read; real ones are a few kilobytes. */
    private static final int MAX_CONTENT_TYPES_BYTES = 256 * 1024;

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
        if (startsWith(b, 0, ZIP_SIGNATURE)) {
            return Optional.empty();  // an XLSX is recognised from the whole file only
        }
        return text(b);
    }

    /**
     * The type of the file, looking at the whole file where the first bytes are not enough (an XLSX workbook is a
     * ZIP archive whose directory is at its end).
     */
    public static Optional<MediaTypes> detect(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(HEAD_BYTES);
        }
        if (startsWith(head, 0, ZIP_SIGNATURE)) {
            return isXlsx(file) ? Optional.of(MediaTypes.XLSX) : Optional.empty();
        }
        return detect(head);
    }

    /** A workbook part, and a {@code [Content_Types].xml} declaring it as a macro-free workbook; no VBA project. */
    private static boolean isXlsx(Path file) {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry types = zip.getEntry("[Content_Types].xml");
            if (types == null || zip.getEntry("xl/workbook.xml") == null || zip.getEntry("xl/vbaProject.bin") != null) {
                return false;
            }
            byte[] content;
            try (InputStream in = zip.getInputStream(types)) {
                content = in.readNBytes(MAX_CONTENT_TYPES_BYTES);
            }
            return new String(content, StandardCharsets.UTF_8).contains(WORKBOOK_CONTENT_TYPE);
        } catch (IOException | RuntimeException e) {
            return false;  // not a readable ZIP archive
        }
    }

    /** XML or plain text: printable characters only, told apart by a leading element. */
    private static Optional<MediaTypes> text(byte[] b) {
        if (b.length == 0) {
            return Optional.empty();
        }
        for (byte value : b) {
            int c = value & 0xFF;
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f' || c == 0x7F) {
                return Optional.empty();
            }
        }
        int start = startsWith(b, 0, UTF8_BOM) ? UTF8_BOM.length : 0;
        while (start < b.length && Character.isWhitespace(b[start])) {
            start++;
        }
        if (start < b.length && b[start] == '<') {
            return isXml(new String(b, start, b.length - start, StandardCharsets.ISO_8859_1)) ? Optional.of(
                MediaTypes.XML) : Optional.empty();
        }
        return Optional.of(MediaTypes.TEXT);
    }

    /**
     * An XML declaration or a first element, and no HTML or SVG: those could run scripts where a browser renders
     * them, so they are refused even though they are only ever served as attachments.
     */
    private static boolean isXml(String head) {
        String lower = head.toLowerCase(Locale.ROOT);
        if (lower.contains("<html") || lower.contains("<svg") || lower.contains("<!doctype html")
            || lower.contains("<script")) {
            return false;
        }
        if (lower.startsWith("<?xml")) {
            return true;
        }
        return head.length() > 1 && (Character.isLetter(head.charAt(1)) || head.charAt(1) == '_');
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
