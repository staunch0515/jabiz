package com.jabiz.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class ExifOrientationTest {

    /** SOI, an APP0, an APP1 with a TIFF whose IFD0 holds {@code entries} (tag, type, count, value), then SOS. */
    static byte[] jpeg(ByteOrder order, int[][] entries) {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + entries.length * 12 + 4).order(order);
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.put(order == ByteOrder.LITTLE_ENDIAN ? (byte) 'I' : (byte) 'M');
        tiff.putShort((short) 42).putInt(8).putShort((short) entries.length);
        for (int[] e : entries) {
            tiff.putShort((short) e[0]).putShort((short) e[1]).putInt(e[2]);
            if (e[1] == 3) {
                tiff.putShort((short) e[3]).putShort((short) 0);
            } else {
                tiff.putInt(e[3]);
            }
        }
        tiff.putInt(0);
        byte[] tiffBytes = tiff.array();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xD8});
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE0, 0, 4, 1, 2});
        int length = 2 + 6 + tiffBytes.length;
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xE1, (byte) (length >> 8), (byte) length});
        out.writeBytes(new byte[] {'E', 'x', 'i', 'f', 0, 0});
        out.writeBytes(tiffBytes);
        out.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xDA, 0, 2});
        return out.toByteArray();
    }

    @Test
    void readsTheOrientationInBothByteOrders() {
        assertThat(ExifOrientation.of(jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x010F, 2, 4, 0}, {0x0112, 3, 1, 6}})))
            .isEqualTo(6);
        assertThat(ExifOrientation.of(jpeg(ByteOrder.LITTLE_ENDIAN, new int[][] {{0x0112, 3, 1, 8}}))).isEqualTo(8);
    }

    @Test
    void fallsBackToNormal() {
        assertThat(ExifOrientation.of(null)).isEqualTo(ExifOrientation.NORMAL);
        assertThat(ExifOrientation.of(new byte[] {1, 2, 3, 4})).isEqualTo(1);
        assertThat(ExifOrientation.of(jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x010F, 2, 4, 0}}))).isEqualTo(1);
        assertThat(ExifOrientation.of(jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x0112, 3, 1, 9}}))).isEqualTo(1);
        assertThat(ExifOrientation.of(jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x0112, 4, 1, 6}}))).isEqualTo(1);
        assertThat(ExifOrientation.of(jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x0112, 3, 2, 6}}))).isEqualTo(1);
        // Truncated inside the APP1 segment.
        byte[] full = jpeg(ByteOrder.BIG_ENDIAN, new int[][] {{0x0112, 3, 1, 6}});
        assertThat(ExifOrientation.of(java.util.Arrays.copyOf(full, 20))).isEqualTo(1);
        // Image data before any EXIF, and a broken marker.
        assertThat(ExifOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDA, 0, 2}))
            .isEqualTo(1);
        assertThat(ExifOrientation.of(new byte[] {(byte) 0xFF, (byte) 0xD8, 0x12, 0x34, 0, 2})).isEqualTo(1);
        // A TIFF header that is not a TIFF header.
        byte[] bad = full.clone();
        bad[16] = 'X';
        assertThat(ExifOrientation.of(bad)).isEqualTo(1);
    }
}
