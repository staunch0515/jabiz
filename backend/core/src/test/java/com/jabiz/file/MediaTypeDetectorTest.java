package com.jabiz.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypeDetectorTest {

    static byte[] bytes(int... values) {
        byte[] b = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            b[i] = (byte) values[i];
        }
        return b;
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }

    /** One MPEG-1 Layer III frame, 128 kbit/s, 44.1 kHz, no padding: 417 bytes. */
    static byte[] mp3Frame() {
        byte[] frame = new byte[417];
        frame[0] = (byte) 0xFF;
        frame[1] = (byte) 0xFB;
        frame[2] = (byte) 0x90;
        frame[3] = (byte) 0x64;
        return frame;
    }

    static byte[] oggPage(byte[] packetStart) {
        byte[] header = new byte[28];
        System.arraycopy(ascii("OggS"), 0, header, 0, 4);
        header[26] = 1; // one segment
        header[27] = (byte) packetStart.length;
        return concat(header, packetStart);
    }

    @Test
    void recognisesEachType() {
        assertThat(MediaTypeDetector.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10))).contains(MediaTypes.JPEG);
        assertThat(MediaTypeDetector.detect(bytes(0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0)))
            .contains(MediaTypes.PNG);
        assertThat(MediaTypeDetector.detect(ascii("%PDF-1.7\n%âãÏÓ"))).contains(MediaTypes.PDF);
        assertThat(MediaTypeDetector.detect(concat(ascii("ID3"), bytes(4, 0, 0, 0, 0, 0x10, 0x00))))
            .contains(MediaTypes.MP3);
        assertThat(MediaTypeDetector.detect(concat(mp3Frame(), mp3Frame()))).contains(MediaTypes.MP3);
        assertThat(MediaTypeDetector.detect(concat(bytes(0, 0, 0, 0x20), ascii("ftypM4A mp42isom"))))
            .contains(MediaTypes.M4A);
        assertThat(MediaTypeDetector.detect(concat(bytes(0, 0, 0, 0x20), ascii("ftypM4B mp42"))))
            .contains(MediaTypes.M4A);
        assertThat(MediaTypeDetector.detect(oggPage(ascii("OpusHead\u0001")))).contains(MediaTypes.OGG);
        assertThat(MediaTypeDetector.detect(oggPage(concat(bytes(1), ascii("vorbis"))))).contains(MediaTypes.OGG);
    }

    @Test
    void refusesScriptsAndMarkupWhateverTheyAreCalled() {
        assertThat(MediaTypeDetector.detect(ascii("<!DOCTYPE html><script>alert(1)</script>"))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"x()\"/>")))
            .isEmpty();
        assertThat(MediaTypeDetector.detect(concat(bytes(0xEF, 0xBB, 0xBF), ascii("<html>")))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("<?xml version=\"1.0\"?><svg/>"))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("GIF89a"))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("RIFF\0\0\0\0WEBPVP8 "))).isEmpty();
    }

    @Test
    void isStrictAboutAmbiguousAudio() {
        // A single frame sync followed by garbage is not MP3.
        byte[] oneFrame = Arrays.copyOf(mp3Frame(), 500);
        assertThat(MediaTypeDetector.detect(oneFrame)).isEmpty();
        // Reserved version and bad bit rate.
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xEB, 0x90, 0), 0)).isZero();
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xFB, 0xF0, 0), 0)).isZero();
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xFB, 0x9C, 0), 0)).isZero();
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xFD, 0x90, 0), 0)).isZero();
        // MPEG-2 and 2.5 Layer III frames are fine.
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xF3, 0x90, 0), 0)).isEqualTo(72 * 80000 / 22050);
        assertThat(MediaTypeDetector.frameLength(bytes(0xFF, 0xE3, 0x90, 0), 0)).isEqualTo(72 * 80000 / 11025);
        // ID3 with a bad version or size is not accepted by itself.
        assertThat(MediaTypeDetector.detect(concat(ascii("ID3"), bytes(9, 0, 0, 0, 0, 0, 0)))).isEmpty();
        assertThat(MediaTypeDetector.detect(concat(ascii("ID3"), bytes(3, 0, 0, 0x80, 0, 0, 0)))).isEmpty();
        // MP4 video brands and bare Ogg pages are refused.
        assertThat(MediaTypeDetector.detect(concat(bytes(0, 0, 0, 0x20), ascii("ftypisom")))).isEmpty();
        assertThat(MediaTypeDetector.detect(concat(bytes(0, 0, 0, 0x20), ascii("ftypmp42")))).isEmpty();
        assertThat(MediaTypeDetector.detect(oggPage(ascii("\u0080theora")))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("OggS"))).isEmpty();
    }

    @Test
    void handlesShortAndMissingInput() {
        assertThat(MediaTypeDetector.detect(null)).isEmpty();
        assertThat(MediaTypeDetector.detect(new byte[0])).isEmpty();
        assertThat(MediaTypeDetector.detect(bytes(0xFF, 0xD8))).isEmpty();
        assertThat(MediaTypeDetector.detect(ascii("%PD"))).isEmpty();
        byte[] large = new byte[MediaTypeDetector.HEAD_BYTES * 2];
        System.arraycopy(ascii("%PDF-"), 0, large, 0, 5);
        assertThat(MediaTypeDetector.detect(large)).contains(MediaTypes.PDF);
    }

    @Test
    void describesTypes() {
        assertThat(MediaTypes.JPEG.isImage()).isTrue();
        assertThat(MediaTypes.PDF.isImage()).isFalse();
        assertThat(MediaTypes.OGG.contentType()).isEqualTo("audio/ogg");
        assertThat(MediaTypes.JPEG.extension()).isEqualTo("jpg");
        assertThat(MediaTypes.JPEG.extensions()).contains(".jpeg");
    }
}
