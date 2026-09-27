package com.jabiz.app.it.file;

import com.jabiz.app.commerce.CommerceFiles;
import com.jabiz.app.it.fixture.ItFileFixtures;
import com.jabiz.runtime.file.FileKeys;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uploading (docs/design/14-files.md sections 1, 3 and 5; ROADMAP 13b acceptance 1 and 2): types are recognised by
 * content, images are re-encoded upright without any metadata and in their variants, anything else is refused.
 */
class FileUploadIT extends FileItSupport {

    private static final byte[] MARKER = FileSamples.SECRET_MARKER.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] EXIF = "Exif".getBytes(StandardCharsets.US_ASCII);

    private static BufferedImage image(byte[] bytes) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(bytes));
    }

    @Test
    void photosLoseEveryMetadataAndAreStoredUprightInTheirVariants() throws IOException {
        byte[] photo = FileSamples.jpegWithExif(1600, 900, 6);
        assertThat(FileSamples.contains(photo, MARKER)).isTrue();

        Map<String, Object> info = uploaded(CommerceFiles.IMAGE, photo, "IMG_0001.jpg", "image/jpeg", photographer());

        UUID fileId = UUID.fromString((String) info.get("fileId"));
        // Orientation 6: turned a quarter clockwise, so the stored image is upright and portrait.
        assertThat(info).containsEntry("contentType", "image/jpeg").containsEntry("width", 900)
            .containsEntry("height", 1600).containsEntry("variants", List.of("w160", "w640"));
        String prefix = FileKeys.prefix(fileId);
        List<String> keys = storedKeys().stream().filter(key -> key.startsWith(prefix)).toList();
        assertThat(keys).containsExactly(prefix + "/original", prefix + "/w160", prefix + "/w640");
        for (String key : keys) {
            byte[] content = stored(key);
            assertThat(FileSamples.contains(content, MARKER)).as("marker in %s", key).isFalse();
            assertThat(FileSamples.contains(content, EXIF)).as("EXIF in %s", key).isFalse();
        }
        BufferedImage original = image(stored(prefix + "/original"));
        assertThat(original.getWidth()).isEqualTo(900);
        assertThat(original.getHeight()).isEqualTo(1600);
        // The red left half of the sensor image is the top after turning clockwise.
        assertThat(new Color(original.getRGB(450, 100)).getRed()).isGreaterThan(200);
        assertThat(new Color(original.getRGB(450, 1500)).getBlue()).isGreaterThan(200);
        BufferedImage small = image(stored(prefix + "/w160"));
        assertThat(small.getWidth()).isEqualTo(160);
        assertThat(small.getHeight()).isEqualTo(284);
        assertThat(image(stored(prefix + "/w640")).getWidth()).isEqualTo(640);
        assertThat(uploadLeftovers()).isEmpty();

        // The stored size and digest are those of the processed original.
        Map<String, Object> row = query("SELECT size_bytes, sha256, original_name, uploaded_by FROM sys_file "
            + "WHERE file_id = ?", fileId).getFirst();
        assertThat(((Number) row.get("size_bytes")).longValue()).isEqualTo(stored(prefix + "/original").length);
        assertThat(row).containsEntry("original_name", "IMG_0001.jpg").containsEntry("uploaded_by", "it-photographer");
    }

    @Test
    void pngKeepsTransparencyButLosesTextChunks() throws IOException {
        byte[] png = FileSamples.pngWithText(300, 200);
        assertThat(FileSamples.contains(png, MARKER)).isTrue();
        Map<String, Object> info = uploaded(CommerceFiles.IMAGE, png, "logo.png", "image/png", photographer());
        String key = FileKeys.key(UUID.fromString((String) info.get("fileId")), "original");
        assertThat(info).containsEntry("contentType", "image/png").containsEntry("variants", List.of("w160"));
        byte[] stored = stored(key);
        assertThat(FileSamples.contains(stored, MARKER)).isFalse();
        assertThat(image(stored).getColorModel().hasAlpha()).isTrue();
    }

    @Test
    void otherTypesAreStoredAsTheyAre() {
        String audio = bearer("it-audio", "it.audio.upload", "it.audio.read");
        for (Object[] sample : new Object[][] {
            {FileSamples.mp3(), "a.mp3", "audio/mpeg"}, {FileSamples.m4a(), "a.m4a", "audio/mp4"},
            {FileSamples.ogg(), "a.ogg", "audio/ogg"}}) {
            byte[] bytes = (byte[]) sample[0];
            // The declared type plays no part.
            Map<String, Object> info = uploaded(ItFileFixtures.AUDIO, bytes, (String) sample[1],
                "application/octet-stream", audio);
            assertThat(info).containsEntry("contentType", sample[2]).containsEntry("sizeBytes", bytes.length);
            assertThat(stored(FileKeys.key(UUID.fromString((String) info.get("fileId")), "original")))
                .isEqualTo(bytes);
        }
        String documents = bearer("it-docs", "commerce.document.upload");
        Map<String, Object> pdf = uploaded(CommerceFiles.DOCUMENT, FileSamples.pdf(), "../../contract.pdf",
            "application/pdf", documents);
        assertThat(pdf).containsEntry("contentType", "application/pdf").containsEntry("width", null)
            .containsEntry("variants", List.of());
        assertThat(query("SELECT original_name FROM sys_file WHERE file_id = ?",
            UUID.fromString((String) pdf.get("fileId"))).getFirst()).containsEntry("original_name", "contract.pdf");
    }

    @Test
    void disguisedMarkupAndWrongTypesAreRefused() {
        List<String> before = storedKeys();
        Object files = query("SELECT count(*) AS n FROM sys_file").getFirst().get("n");
        Map<String, Object> html = upload(CommerceFiles.IMAGE, FileSamples.html(), "photo.jpg", "image/jpeg",
            photographer()).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(html)).containsExactly("FILE_TYPE_NOT_ALLOWED");
        upload(CommerceFiles.IMAGE, FileSamples.svg(), "icon.png", "image/png", photographer())
            .expectStatus().isBadRequest();
        String documents = bearer("it-docs", "commerce.document.upload");
        upload(CommerceFiles.DOCUMENT, FileSamples.html(), "report.pdf", "application/pdf", documents)
            .expectStatus().isBadRequest();
        // A recognised type the policy does not accept.
        Map<String, Object> pdf = upload(CommerceFiles.IMAGE, FileSamples.pdf(), "scan.jpg", "image/jpeg",
            photographer()).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(pdf)).containsExactly("FILE_TYPE_NOT_ALLOWED");
        assertThat(storedKeys()).isEqualTo(before);
        assertThat(uploadLeftovers()).isEmpty();
        assertThat(query("SELECT count(*) AS n FROM sys_file").getFirst().get("n")).isEqualTo(files);
    }

    @Test
    void brokenAndOversizedImagesAreRefusedBeforeDecoding() {
        List<String> before = storedKeys();
        Map<String, Object> bomb = upload(CommerceFiles.IMAGE, FileSamples.pngClaiming(50_000, 50_000), "bomb.png",
            "image/png", photographer()).expectStatus().isBadRequest().expectBody(MAP).returnResult()
            .getResponseBody();
        assertThat(ruleCodes(bomb)).containsExactly("FILE_INVALID");
        byte[] broken = new byte[2000];
        broken[0] = (byte) 0xFF;
        broken[1] = (byte) 0xD8;
        broken[2] = (byte) 0xFF;
        Map<String, Object> garbage = upload(CommerceFiles.IMAGE, broken, "broken.jpg", "image/jpeg", photographer())
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(garbage)).containsExactly("FILE_INVALID");
        assertThat(storedKeys()).isEqualTo(before);
        assertThat(uploadLeftovers()).isEmpty();
    }

    @Test
    void permissionsPolicyAndPartsAreChecked() {
        List<String> before = storedKeys();
        upload(CommerceFiles.IMAGE, FileSamples.jpeg(10, 10), "a.jpg", "image/jpeg",
            bearer("it-reader", "commerce.media.read")).expectStatus().isForbidden();
        upload("no.such.policy", FileSamples.jpeg(10, 10), "a.jpg", "image/jpeg", photographer())
            .expectStatus().isNotFound();
        uploadParts(CommerceFiles.IMAGE, null, new Part("file", "a.jpg", "image/jpeg", FileSamples.jpeg(10, 10)))
            .expectStatus().isUnauthorized();

        Map<String, Object> extra = uploadParts(CommerceFiles.IMAGE, photographer(),
            new Part("note", null, null, "hello".getBytes(StandardCharsets.UTF_8)),
            new Part("file", "a.jpg", "image/jpeg", FileSamples.jpeg(10, 10)))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(extra)).containsExactly("FILE_INVALID");
        Map<String, Object> twoFiles = uploadParts(CommerceFiles.IMAGE, photographer(),
            new Part("file", "a.jpg", "image/jpeg", FileSamples.jpeg(10, 10)),
            new Part("file", "b.jpg", "image/jpeg", FileSamples.jpeg(10, 10)))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(twoFiles)).containsExactly("FILE_INVALID");
        Map<String, Object> noFile = uploadParts(CommerceFiles.IMAGE, photographer(),
            new Part("note", null, null, "hello".getBytes(StandardCharsets.UTF_8)))
            .expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(noFile)).containsExactly("FILE_INVALID");

        Map<String, Object> empty = upload(CommerceFiles.IMAGE, new byte[0], "empty.jpg", "image/jpeg",
            photographer()).expectStatus().isBadRequest().expectBody(MAP).returnResult().getResponseBody();
        assertThat(ruleCodes(empty)).containsExactly("FILE_INVALID");
        assertThat(storedKeys()).isEqualTo(before);
        assertThat(uploadLeftovers()).isEmpty();
    }
}
