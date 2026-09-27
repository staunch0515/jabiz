package com.jabiz.runtime.file;

import com.jabiz.entity.ValidationException;
import com.jabiz.entity.Violation;
import com.jabiz.file.ExifOrientation;
import com.jabiz.file.FilePolicy;
import com.jabiz.file.MediaTypes;
import com.jabiz.i18n.PlatformErrorCodes;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Re-encodes uploaded images (docs/design/14-files.md section 3, decision D18): checks the pixel count from the
 * header before decoding (decompression bombs), decodes, turns the image upright by its EXIF orientation, and writes
 * it again, JPEG at quality 0.85 or PNG, which drops every metadata block (EXIF with GPS, XMP, comments, ICC). Width
 * variants are produced from the upright image; none is wider than the original. The original bytes are never kept.
 *
 * <p>Decoding and encoding block and use the CPU, so all of it runs on {@code boundedElastic}.
 */
@Component
public class ImageProcessor {

    /** JPEG quality of re-encoded images. */
    static final float JPEG_QUALITY = 0.85f;

    /** Bytes read to find the EXIF orientation; APP1 comes before the image data. */
    private static final int EXIF_HEAD = 128 * 1024;

    static {
        // Rendering needs no display; ImageIO's disk cache would write temporary files outside the storage area.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        ImageIO.setUseCache(false);
    }

    /**
     * The processed image: the upright original and its variants, as files in the work directory.
     *
     * @param variants variant name ({@code w<width>}) to file, narrowest first
     */
    public record ProcessedImage(int width, int height, Path original, Map<String, Path> variants) {}

    /**
     * Processes {@code source}, which the caller recognised as {@code type}, writing the results into {@code work}.
     *
     * @throws ValidationException {@code FILE_INVALID} when the image is too large or cannot be decoded
     */
    public Mono<ProcessedImage> process(Path source, MediaTypes type, FilePolicy.ImageOptions options, Path work) {
        return Mono.fromCallable(() -> processBlocking(source, type, options, work))
            .subscribeOn(Schedulers.boundedElastic());
    }

    ProcessedImage processBlocking(Path source, MediaTypes type, FilePolicy.ImageOptions options, Path work)
        throws IOException {
        if (!type.isImage()) {
            throw new IllegalArgumentException(type + " is not an image type");
        }
        BufferedImage decoded = decode(source, type, options.maxPixels());
        int orientation = type == MediaTypes.JPEG ? ExifOrientation.of(head(source)) : ExifOrientation.NORMAL;
        BufferedImage upright = orient(normalise(decoded, type), orientation);

        Files.createDirectories(work);
        Path original = work.resolve(FileKeys.ORIGINAL);
        encode(upright, type, original);
        Map<String, Path> variants = new LinkedHashMap<>();
        for (int width : options.variants()) {
            if (width >= upright.getWidth()) {
                continue;
            }
            String name = FilePolicy.ImageOptions.variantName(width);
            Path file = work.resolve(name);
            encode(scale(upright, width), type, file);
            variants.put(name, file);
        }
        return new ProcessedImage(upright.getWidth(), upright.getHeight(), original, variants);
    }

    private static BufferedImage decode(Path source, MediaTypes type, long maxPixels) throws IOException {
        try (ImageInputStream input = ImageIO.createImageInputStream(source.toFile())) {
            if (input == null) {
                throw invalid("the image cannot be opened");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(type.extension().equals("jpg")
                ? "jpeg" : type.extension());
            if (!readers.hasNext()) {
                throw invalid("no decoder for " + type);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw invalid("the image has no pixels");
                }
                if (width * height > maxPixels) {
                    throw invalid("the image has " + width + " x " + height + " pixels, more than " + maxPixels);
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw invalid("the image cannot be decoded");
                }
                return image;
            } catch (IOException | RuntimeException e) {
                if (e instanceof ValidationException validation) {
                    throw validation;
                }
                throw invalid("the image cannot be decoded (" + e.getClass().getSimpleName() + ")");
            } finally {
                reader.dispose();
            }
        }
    }

    /** JPEG has no transparency: any image is drawn onto an RGB canvas. PNG keeps its alpha channel. */
    private static BufferedImage normalise(BufferedImage image, MediaTypes type) {
        boolean alpha = image.getColorModel().hasAlpha();
        int target = type == MediaTypes.JPEG || !alpha ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB;
        if (image.getType() == target) {
            return image;
        }
        BufferedImage copy = new BufferedImage(image.getWidth(), image.getHeight(), target);
        Graphics2D g = copy.createGraphics();
        try {
            if (target == BufferedImage.TYPE_INT_RGB) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, image.getWidth(), image.getHeight());
            }
            g.drawImage(image, 0, 0, null);
        } finally {
            g.dispose();
        }
        return copy;
    }

    /** Applies an EXIF orientation (1 to 8) so that the result is upright. */
    static BufferedImage orient(BufferedImage image, int orientation) {
        if (orientation <= ExifOrientation.NORMAL || orientation > 8) {
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        boolean swap = orientation >= 5;
        AffineTransform t = new AffineTransform();
        switch (orientation) {
            case 2 -> { t.translate(w, 0); t.scale(-1, 1); }
            case 3 -> { t.translate(w, h); t.rotate(Math.PI); }
            case 4 -> { t.translate(0, h); t.scale(1, -1); }
            case 5 -> { t.rotate(Math.PI / 2); t.scale(1, -1); }
            case 6 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 7 -> { t.scale(-1, 1); t.translate(-h, 0); t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            case 8 -> { t.translate(0, w); t.rotate(3 * Math.PI / 2); }
            default -> { }
        }
        BufferedImage result = new BufferedImage(swap ? h : w, swap ? w : h, image.getType());
        Graphics2D g = result.createGraphics();
        try {
            g.drawImage(image, t, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /** Scales to {@code width}, halving step by step first so that large reductions keep their detail. */
    static BufferedImage scale(BufferedImage image, int width) {
        BufferedImage current = image;
        while (current.getWidth() / 2 >= width) {
            current = resize(current, current.getWidth() / 2);
        }
        return current.getWidth() == width ? current : resize(current, width);
    }

    private static BufferedImage resize(BufferedImage image, int width) {
        int height = Math.max(1, (int) Math.round(image.getHeight() * (double) width / image.getWidth()));
        BufferedImage result = new BufferedImage(width, height, image.getType());
        Graphics2D g = result.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(image, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return result;
    }

    /** Writes without any metadata: JPEG gets only the writer's minimal JFIF header. */
    private static void encode(BufferedImage image, MediaTypes type, Path target) throws IOException {
        String format = type == MediaTypes.JPEG ? "jpeg" : "png";
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format).next();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(target.toFile())) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (type == MediaTypes.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, List.of(), null), param);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] head(Path source) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            return in.readNBytes(EXIF_HEAD);
        }
    }

    private static ValidationException invalid(String reason) {
        return new ValidationException(List.of(new Violation("file", PlatformErrorCodes.FILE_INVALID,
            "The image cannot be processed: " + reason)));
    }
}
