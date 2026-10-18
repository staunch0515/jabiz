package com.jabiz.quizbuks.content.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws an AI cover ({@link CoverDesigns.Design}) as a {@value #WIDTH} × {@value #HEIGHT} PNG (docs/quizbuks/plans/Q8-ai.md,
 * Q8b, A-4 option A): the palette's gradient, the motif in the accent colour, and the text in bold, wrapped to at most
 * {@value #MAX_LINES} lines at the largest size that fits. Pure Java2D on the server (headless), deterministic: the
 * same design gives the same picture.
 *
 * <p>Fonts: the platform's Noto Sans Bold (Latin, Greek, Cyrillic) first, then the fonts the deployment configures for
 * PDFs ({@code jabiz.reports.pdf.fonts}: Japanese and Chinese in the image), then the JVM's own sans serif; each
 * character is drawn in the first that has it. A text with a character none has is not drawable
 * ({@link #drawable}): the design is dropped rather than drawn with empty boxes. The fonts are read once, when the bean
 * is made: a job's handler reads no file at run time other than its own job's.
 *
 * <p>The platform stores the picture as an upload of the requester (decision D50): it is recognised from its content
 * and re-encoded like any uploaded image.
 */
@Component
public class CoverRenderer {

    public static final int WIDTH = 1200;
    public static final int HEIGHT = 630;
    static final int MAX_LINES = 3;
    private static final int LARGEST = 92;
    private static final int SMALLEST = 36;

    private static final Logger log = LoggerFactory.getLogger(CoverRenderer.class);

    private final List<Font> fonts;

    CoverRenderer(@Value("${jabiz.reports.pdf.fonts:}") List<String> fallbackPaths) {
        List<Font> loaded = new ArrayList<>();
        loaded.add(platformFont());
        for (String path : fallbackPaths == null ? List.<String>of() : fallbackPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            try (InputStream in = Files.newInputStream(Path.of(path.strip()))) {
                loaded.add(Font.createFont(Font.TRUETYPE_FONT, in).deriveFont(Font.BOLD));
            } catch (IOException | FontFormatException | RuntimeException e) {
                // The platform's startup check (report fonts) names the broken file; covers go on without it.
                log.warn("The cover renderer cannot read the font {}: {}", path, e.getClass().getSimpleName());
            }
        }
        loaded.add(new Font(Font.SANS_SERIF, Font.BOLD, 1));
        this.fonts = List.copyOf(loaded);
    }

    /** A renderer with the platform's font and the JVM's only, for tests. */
    static CoverRenderer withoutFallbacks() {
        return new CoverRenderer(List.of());
    }

    private static Font platformFont() {
        try (InputStream in = CoverRenderer.class.getResourceAsStream("/jabiz/fonts/NotoSans-Bold.ttf")) {
            if (in == null) {
                return new Font(Font.SANS_SERIF, Font.BOLD, 1);
            }
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (FontFormatException e) {
            throw new IllegalStateException("The platform's font cannot be read", e);
        }
    }

    /**
     * Whether every character of {@code text} (white space aside) is an assigned character, neither a control, a
     * format nor a private use one, and in one of the fonts.
     */
    public boolean drawable(String text) {
        return text != null && text.codePoints().allMatch(cp -> Character.isWhitespace(cp)
            || (Character.isDefined(cp) && !Character.isISOControl(cp)
                && Character.getType(cp) != Character.FORMAT && Character.getType(cp) != Character.PRIVATE_USE
                && Character.getType(cp) != Character.SURROGATE && fontFor(cp) != null));
    }

    private Font fontFor(int codePoint) {
        for (Font font : fonts) {
            if (font.canDisplay(codePoint)) {
                return font;
            }
        }
        return null;
    }

    /**
     * The design as a PNG.
     *
     * @throws IllegalArgumentException when the design is not of the catalogue or its text is not drawable
     */
    public byte[] render(CoverDesigns.Design design) {
        CoverDesigns.Palette palette = CoverDesigns.palette(design.palette());
        if (palette == null || !CoverDesigns.layout(design.layout()) || !CoverDesigns.motif(design.motif())
            || !drawable(design.text())) {
            throw new IllegalArgumentException("Not a drawable cover design");
        }
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g.setPaint(new GradientPaint(0, 0, new Color(palette.from()), WIDTH, HEIGHT, new Color(palette.to())));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            motif(g, design.motif(), design.layout(), new Color(palette.accent()));
            text(g, design, palette);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---- the motif -------------------------------------------------------------------------------------------------

    private static void motif(Graphics2D g, String motif, String layout, Color accent) {
        // The motif keeps clear of the text: on the right for "left", the upper right for "corner".
        int fromX = switch (layout) {
            case "left" -> WIDTH / 2;
            case "corner" -> WIDTH / 3;
            default -> 0;
        };
        int toY = "corner".equals(layout) ? HEIGHT * 2 / 3 : HEIGHT;
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.28f));
        g.setColor(accent);
        switch (motif) {
            case "circles" -> {
                int[][] circles = {{fromX + 140, 120, 260}, {WIDTH - 180, toY - 160, 340}, {fromX + 420, toY - 60, 180},
                    {WIDTH - 420, 40, 150}};
                for (int[] c : circles) {
                    g.fill(new Ellipse2D.Double(c[0] - c[2] / 2.0, c[1] - c[2] / 2.0, c[2], c[2]));
                }
            }
            case "waves" -> {
                for (int band = 0; band < 3; band++) {
                    Path2D wave = new Path2D.Double();
                    double base = HEIGHT - 70 - band * 55;
                    wave.moveTo(fromX, HEIGHT);
                    for (int x = fromX; x <= WIDTH; x += 10) {
                        wave.lineTo(x, base + Math.sin((x + band * 90) / 95.0) * 28);
                    }
                    wave.lineTo(WIDTH, HEIGHT);
                    wave.closePath();
                    g.fill(wave);
                }
            }
            case "grid" -> {
                g.setStroke(new BasicStroke(2f));
                for (int x = fromX; x <= WIDTH; x += 48) {
                    g.drawLine(x, 0, x, toY);
                }
                for (int y = 0; y <= toY; y += 48) {
                    g.drawLine(fromX, y, WIDTH, y);
                }
            }
            case "stripes" -> {
                for (int x = fromX - HEIGHT; x < WIDTH; x += 110) {
                    Path2D stripe = new Path2D.Double();
                    stripe.moveTo(x, toY);
                    stripe.lineTo(x + 50, toY);
                    stripe.lineTo(x + 50 + toY, 0);
                    stripe.lineTo(x + toY, 0);
                    stripe.closePath();
                    g.fill(stripe);
                }
            }
            case "dots" -> {
                for (int y = 30; y < toY; y += 46) {
                    for (int x = fromX + 30 + (y / 46 % 2) * 23; x < WIDTH; x += 46) {
                        g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
                    }
                }
            }
            case "rays" -> {
                for (int ray = 0; ray < 9; ray++) {
                    double from = Math.toRadians(180 + ray * 10);
                    double to = from + Math.toRadians(5);
                    Path2D wedge = new Path2D.Double();
                    wedge.moveTo(WIDTH, 0);
                    wedge.lineTo(WIDTH + Math.cos(from) * 1600, -Math.sin(from) * 1600);
                    wedge.lineTo(WIDTH + Math.cos(to) * 1600, -Math.sin(to) * 1600);
                    wedge.closePath();
                    g.fill(wedge);
                }
            }
            default -> {
                // "plain": the gradient alone.
            }
        }
        g.setComposite(AlphaComposite.SrcOver);
    }

    // ---- the text --------------------------------------------------------------------------------------------------

    private void text(Graphics2D g, CoverDesigns.Design design, CoverDesigns.Palette palette) {
        String layout = design.layout();
        int maxWidth = switch (layout) {
            // From x = 110 to short of the motif, which starts halfway.
            case "left" -> WIDTH / 2 - 140;
            case "corner" -> WIDTH * 2 / 3;
            default -> WIDTH - 220;
        };
        FontRenderContext frc = g.getFontRenderContext();
        List<TextLayout> lines = null;
        int size = LARGEST;
        for (; size >= SMALLEST; size -= 4) {
            lines = wrap(design.text(), size, maxWidth, frc);
            if (lines != null) {
                break;
            }
        }
        if (lines == null) {
            size = SMALLEST;
            lines = force(design.text(), size, maxWidth, frc);
        }
        double lineHeight = size * 1.25;
        double block = lineHeight * lines.size();
        double top = switch (layout) {
            case "corner" -> HEIGHT - 70 - block;
            default -> (HEIGHT - block) / 2;
        };
        if ("band".equals(layout)) {
            g.setColor(new Color(palette.panel()));
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.9f));
            g.fill(new Rectangle2D.Double(0, top - 36, WIDTH, block + 72));
            g.setComposite(AlphaComposite.SrcOver);
        }
        if ("left".equals(layout)) {
            g.setColor(new Color(palette.accent()));
            g.fill(new Rectangle2D.Double(70, top - 10, 14, block + 20));
        }
        Color text = new Color(palette.text());
        boolean light = (text.getRed() + text.getGreen() + text.getBlue()) / 3 > 128;
        Color shadow = light ? new Color(0, 0, 0, 90) : new Color(255, 255, 255, 110);
        for (int i = 0; i < lines.size(); i++) {
            TextLayout line = lines.get(i);
            double width = line.getAdvance();
            double x = switch (layout) {
                case "left" -> 110;
                case "corner" -> 80;
                default -> (WIDTH - width) / 2;
            };
            float baseline = (float) (top + lineHeight * i + size);
            g.setColor(shadow);
            line.draw(g, (float) x + 3, baseline + 3);
            g.setColor(text);
            line.draw(g, (float) x, baseline);
        }
    }

    /** The text in at most {@value #MAX_LINES} lines of at most {@code maxWidth}; null when it does not fit. */
    private List<TextLayout> wrap(String text, int size, int maxWidth, FontRenderContext frc) {
        List<String> words = words(text);
        List<TextLayout> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            String tried = line + word;
            if (line.isEmpty() || width(tried.strip(), size, frc) <= maxWidth) {
                line.append(word);
                if (width(line.toString().strip(), size, frc) > maxWidth) {
                    return null;
                }
                continue;
            }
            lines.add(layout(line.toString().strip(), size, frc));
            line.setLength(0);
            line.append(word.stripLeading());
            if (lines.size() >= MAX_LINES) {
                return null;
            }
        }
        if (!line.isEmpty()) {
            lines.add(layout(line.toString().strip(), size, frc));
        }
        return lines.size() <= MAX_LINES ? lines : null;
    }

    /** The text cut into lines by width alone (at the smallest size: it always ends up drawn whole). */
    private List<TextLayout> force(String text, int size, int maxWidth, FontRenderContext frc) {
        List<TextLayout> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        text.codePoints().forEach(cp -> {
            String tried = line.toString() + Character.toString(cp);
            if (!line.isEmpty() && width(tried, size, frc) > maxWidth) {
                lines.add(layout(line.toString().strip(), size, frc));
                line.setLength(0);
            }
            line.appendCodePoint(cp);
        });
        if (!line.toString().isBlank()) {
            lines.add(layout(line.toString().strip(), size, frc));
        }
        return lines;
    }

    /**
     * Pieces a line may break between: a word with the space before it, or one character of a script written without
     * spaces (Chinese, Japanese).
     */
    static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        text.codePoints().forEach(cp -> {
            if (Character.isIdeographic(cp) || isKana(cp) || Character.UnicodeScript.of(cp)
                == Character.UnicodeScript.HANGUL) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
                words.add(Character.toString(cp));
            } else if (Character.isWhitespace(cp)) {
                if (!word.isEmpty()) {
                    words.add(word.toString());
                    word.setLength(0);
                }
                word.append(' ');
            } else {
                word.appendCodePoint(cp);
            }
        });
        if (!word.isEmpty()) {
            words.add(word.toString());
        }
        return words;
    }

    private static boolean isKana(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HIRAGANA || script == Character.UnicodeScript.KATAKANA
            || (cp >= 0x3000 && cp <= 0x303F) || (cp >= 0xFF00 && cp <= 0xFFEF);
    }

    private double width(String text, int size, FontRenderContext frc) {
        return text.isEmpty() ? 0 : layout(text, size, frc).getAdvance();
    }

    /** The text with each run of characters in the first font that has them. */
    private TextLayout layout(String text, int size, FontRenderContext frc) {
        String shown = text.isEmpty() ? " " : text;
        AttributedString attributed = new AttributedString(shown);
        int start = 0;
        Font current = null;
        for (int i = 0; i < shown.length(); ) {
            int cp = shown.codePointAt(i);
            Font font = fontFor(cp);
            if (font == null) {
                font = fonts.getFirst();
            }
            if (current != null && !font.equals(current)) {
                attributed.addAttribute(TextAttribute.FONT, current.deriveFont(Font.BOLD, (float) size), start, i);
                start = i;
            }
            current = font;
            i += Character.charCount(cp);
        }
        attributed.addAttribute(TextAttribute.FONT, current.deriveFont(Font.BOLD, (float) size), start,
            shown.length());
        return new TextLayout(attributed.getIterator(), frc);
    }
}
