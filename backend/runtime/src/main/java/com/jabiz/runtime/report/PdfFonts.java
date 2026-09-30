package com.jabiz.runtime.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The fonts of PDF reports (docs/design/19-reports.md section 4): Noto Sans, which the platform ships (Latin, Greek,
 * Cyrillic; SIL Open Font License), and the application's fallback fonts for characters it lacks, such as Chinese or
 * Japanese. Each is read once; every document embeds the subset it uses.
 *
 * @param regular   Noto Sans Regular
 * @param bold      Noto Sans Bold
 * @param fallbacks TrueType or OpenType (TrueType outlines) fonts tried in order for characters Noto Sans lacks
 */
public record PdfFonts(byte[] regular, byte[] bold, List<byte[]> fallbacks) {

    public PdfFonts {
        Objects.requireNonNull(regular, "regular must not be null");
        Objects.requireNonNull(bold, "bold must not be null");
        fallbacks = List.copyOf(fallbacks);
    }

    /** The platform's fonts, then the fallback font files at the given paths. */
    public static PdfFonts load(List<String> fallbackPaths) {
        List<byte[]> fallbacks = new ArrayList<>();
        for (String path : fallbackPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try {
                fallbacks.add(Files.readAllBytes(Path.of(path.trim())));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read the report font " + path, e);
            }
        }
        return new PdfFonts(resource("NotoSans-Regular.ttf"), resource("NotoSans-Bold.ttf"), fallbacks);
    }

    private static byte[] resource(String name) {
        try (InputStream in = PdfFonts.class.getResourceAsStream("/jabiz/fonts/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing platform font jabiz/fonts/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
