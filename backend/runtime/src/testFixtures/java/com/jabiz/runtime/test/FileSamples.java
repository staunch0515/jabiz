package com.jabiz.runtime.test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Sample file contents for the file tests (docs/design/14-files.md section 10), generated rather than checked in: images
 * with EXIF (orientation and GPS) and PNG text chunks, a PNG header claiming more pixels than it has, and the smallest
 * valid heads of the other types.
 */
public final class FileSamples {

    /** Text planted in metadata; it must not survive processing. */
    public static final String SECRET_MARKER = "GPS-SECRET-35.6812N-139.7671E";

    private FileSamples() {}

    /** A plain JPEG of the given size: the left half red, the right half blue. */
    public static byte[] jpeg(int width, int height) {
        return encode(picture(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
    }

    /**
     * A JPEG whose EXIF block holds {@code orientation}, a GPS position, and {@link #SECRET_MARKER} as the image
     * description.
     */
    public static byte[] jpegWithExif(int width, int height, int orientation) {
        byte[] plain = jpeg(width, height);
        byte[] app1 = exifSegment(orientation);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, 2); // SOI
        out.writeBytes(app1);
        out.write(plain, 2, plain.length - 2);
        return out.toByteArray();
    }

    /** A PNG with transparency and a {@code tEXt} chunk holding {@link #SECRET_MARKER}. */
    public static byte[] pngWithText(int width, int height) {
        byte[] plain = encode(picture(width, height, BufferedImage.TYPE_INT_ARGB), "png");
        // After the signature (8) and IHDR (8 + 13 + 4) comes the place for a text chunk.
        int at = 8 + 25;
        byte[] chunk = pngChunk("tEXt", ("Comment\0" + SECRET_MARKER).getBytes(StandardCharsets.ISO_8859_1));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(plain, 0, at);
        out.writeBytes(chunk);
        out.write(plain, at, plain.length - at);
        return out.toByteArray();
    }

    /** A small PNG whose header claims {@code width} x {@code height} pixels (a decompression bomb's header). */
    public static byte[] pngClaiming(int width, int height) {
        byte[] png = encode(picture(8, 8, BufferedImage.TYPE_INT_RGB), "png");
        ByteBuffer ihdr = ByteBuffer.wrap(png, 16, 8).order(ByteOrder.BIG_ENDIAN);
        ihdr.putInt(width).putInt(height);
        CRC32 crc = new CRC32();
        crc.update(png, 12, 4 + 13);
        ByteBuffer.wrap(png, 29, 4).order(ByteOrder.BIG_ENDIAN).putInt((int) crc.getValue());
        return png;
    }

    /** The start of a PDF; enough for recognition and to be stored and served. */
    public static byte[] pdf() {
        return ("%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\ntrailer << /Root 1 0 R >>\n%%EOF\n" + "x".repeat(200))
            .getBytes(StandardCharsets.ISO_8859_1);
    }

    /** An MP3 with an ID3v2 tag and some frames. */
    public static byte[] mp3() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {'I', 'D', '3', 4, 0, 0, 0, 0, 0, 0});
        for (int i = 0; i < 4; i++) {
            byte[] frame = new byte[417];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFB;
            frame[2] = (byte) 0x90;
            frame[3] = (byte) 0x64;
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }

    /** An AAC audio file's {@code ftyp} box and some padding. */
    public static byte[] m4a() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0, 0, 0, 0x20});
        out.writeBytes("ftypM4A \0\0\0\0M4A mp42isom\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes(new byte[256]);
        return out.toByteArray();
    }

    /** An Ogg page whose first packet is an Opus identification header. */
    public static byte[] ogg() {
        byte[] page = new byte[28 + 19 + 100];
        System.arraycopy("OggS".getBytes(StandardCharsets.ISO_8859_1), 0, page, 0, 4);
        page[26] = 1;
        page[27] = 19;
        System.arraycopy("OpusHead".getBytes(StandardCharsets.ISO_8859_1), 0, page, 28, 8);
        return page;
    }

    /** Markup that would run a script if a browser rendered it. */
    public static byte[] html() {
        return "<!DOCTYPE html><html><body><script>alert(document.cookie)</script></body></html>"
            .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] svg() {
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"><rect width=\"1\" height=\"1\"/></svg>"
            .getBytes(StandardCharsets.UTF_8);
    }

    /** Whether {@code haystack} contains {@code needle} as bytes. */
    public static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static BufferedImage picture(int width, int height, int type) {
        BufferedImage image = new BufferedImage(width, height, type);
        Graphics2D g = image.createGraphics();
        try {
            g.setColor(Color.RED);
            g.fillRect(0, 0, width / 2, height);
            g.setColor(Color.BLUE);
            g.fillRect(width / 2, 0, width - width / 2, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** APP1 "Exif": a big-endian TIFF whose IFD0 has an image description, the orientation and a GPS IFD. */
    private static byte[] exifSegment(int orientation) {
        byte[] description = (SECRET_MARKER + "\0").getBytes(StandardCharsets.US_ASCII);
        int ifd0Entries = 3;
        int ifd0 = 8;
        int ifd0Size = 2 + ifd0Entries * 12 + 4;
        int gpsIfd = ifd0 + ifd0Size;
        int gpsEntries = 2;
        int gpsSize = 2 + gpsEntries * 12 + 4;
        int descriptionAt = gpsIfd + gpsSize;
        int latitudeAt = descriptionAt + description.length;
        ByteBuffer tiff = ByteBuffer.allocate(latitudeAt + 24).order(ByteOrder.BIG_ENDIAN);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(ifd0);
        tiff.putShort((short) ifd0Entries);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(description.length).putInt(descriptionAt);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(gpsIfd);
        tiff.putInt(0);
        tiff.putShort((short) gpsEntries);
        tiff.putShort((short) 0x0001).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
        tiff.putShort((short) 0x0002).putShort((short) 5).putInt(3).putInt(latitudeAt);
        tiff.putInt(0);
        tiff.put(description);
        tiff.putInt(35).putInt(1).putInt(40).putInt(1).putInt(5243).putInt(100);
        byte[] body = tiff.array();
        int length = 2 + 6 + body.length;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(body);
        return out.toByteArray();
    }

    private static byte[] pngChunk(String type, byte[] data) {
        ByteBuffer chunk = ByteBuffer.allocate(12 + data.length).order(ByteOrder.BIG_ENDIAN);
        chunk.putInt(data.length);
        chunk.put(type.getBytes(StandardCharsets.US_ASCII));
        chunk.put(data);
        CRC32 crc = new CRC32();
        crc.update(chunk.array(), 4, 4 + data.length);
        chunk.putInt((int) crc.getValue());
        return chunk.array();
    }
}
