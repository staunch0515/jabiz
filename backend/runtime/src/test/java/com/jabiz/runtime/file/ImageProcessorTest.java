package com.jabiz.runtime.file;

import com.jabiz.entity.ValidationException;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.runtime.test.FileSamples;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor(1);

    @TempDir
    Path temp;

    private Path write(byte[] bytes) throws IOException {
        return Files.write(temp.resolve("upload"), bytes);
    }

    @Test
    void refusesTooManyPixelsFromTheHeaderAlone() throws IOException {
        Path bomb = write(FileSamples.pngClaiming(50_000, 50_000));
        assertThatThrownBy(() -> processor.processBlocking(bomb, MediaTypes.PNG,
            new FilePolicy.ImageOptions(40_000_000, List.of()), temp.resolve("work")))
            .isInstanceOf(ValidationException.class)
            .hasMessageContaining("FILE_INVALID");
        assertThat(temp.resolve("work")).doesNotExist();
    }

    @Test
    void appliesEveryOrientation() {
        BufferedImage source = new BufferedImage(4, 2, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB()); // top left marked
        int[][] expected = {
            {1, 0, 0}, {2, 3, 0}, {3, 3, 1}, {4, 0, 1}, {5, 0, 0}, {6, 1, 0}, {7, 1, 3}, {8, 0, 3}};
        for (int[] e : expected) {
            BufferedImage turned = ImageProcessor.orient(source, e[0]);
            boolean swap = e[0] >= 5;
            assertThat(turned.getWidth()).as("width, orientation %d", e[0]).isEqualTo(swap ? 2 : 4);
            assertThat(new Color(turned.getRGB(e[1], e[2]))).as("marked corner, orientation %d", e[0])
                .isEqualTo(Color.RED);
        }
        assertThat(ImageProcessor.orient(source, 0)).isSameAs(source);
        assertThat(ImageProcessor.orient(source, 9)).isSameAs(source);
    }

    @Test
    void scalesDownOnlyAndKeepsTheAspectRatio() throws IOException {
        Path jpeg = write(FileSamples.jpeg(1000, 500));
        ImageProcessor.ProcessedImage result = processor.processBlocking(jpeg, MediaTypes.JPEG,
            new FilePolicy.ImageOptions(1_000_000, List.of(100, 999, 1000, 2000)), temp.resolve("work"));
        assertThat(result.width()).isEqualTo(1000);
        assertThat(result.variants()).containsOnlyKeys("w100", "w999");
        BufferedImage small = ImageIO.read(result.variants().get("w100").toFile());
        assertThat(small.getWidth()).isEqualTo(100);
        assertThat(small.getHeight()).isEqualTo(50);
        assertThatThrownBy(() -> processor.processBlocking(jpeg, MediaTypes.PDF,
            FilePolicy.ImageOptions.DEFAULT, temp.resolve("x"))).isInstanceOf(IllegalArgumentException.class);
    }
}
