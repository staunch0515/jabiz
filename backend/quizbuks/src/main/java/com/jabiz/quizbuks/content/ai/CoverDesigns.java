package com.jabiz.quizbuks.content.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The fixed catalogue AI covers are designed from (docs/quizbuks/plans/Q8-ai.md, Q8b, A-4 option A): Claude does not
 * draw. It picks, for each of {@value #COVERS} covers, one palette, one layout and one motif of this catalogue and
 * writes the cover's text (at most {@value #MAX_TEXT} characters); the server draws the picture
 * ({@link CoverRenderer}). Nothing photographic or copied, every cover in the same style, one provider only.
 *
 * <p>A design is checked before it is drawn ({@link #check}): names outside the catalogue, a blank or too long text,
 * control characters or a character no configured font can draw drop it (counted as invalid). The catalogue's names
 * are part of the answer's schema (enums), so the provider rarely strays from it.
 */
public final class CoverDesigns {

    /** Covers asked for and kept at most. */
    public static final int COVERS = 3;
    /** The longest cover text, in characters (code points). */
    public static final int MAX_TEXT = 40;

    /**
     * Colours of one palette, as RGB: the background's gradient from {@code from} to {@code to}, the motif's
     * {@code accent}, the text, and the {@code panel} behind the text of the band layout.
     */
    public record Palette(String name, String description, int from, int to, int accent, int text, int panel) {}

    public static final List<Palette> PALETTES = List.of(
        new Palette("ocean", "deep blue to bright blue, calm and trustworthy", 0x0B3D91, 0x1565C0, 0x90CAF9,
            0xFFFFFF, 0x08306B),
        new Palette("sunset", "deep red to orange, warm and energetic", 0x8E1B1B, 0xC43E00, 0xFFE082, 0xFFFFFF,
            0x5D0F0F),
        new Palette("forest", "dark green to green, natural and fresh", 0x1B5E20, 0x2E7D32, 0xC5E1A5, 0xFFFFFF,
            0x0D3B12),
        new Palette("berry", "violet to magenta, playful and bold", 0x4A148C, 0x880E4F, 0xF8BBD0, 0xFFFFFF,
            0x311B92),
        new Palette("slate", "charcoal to grey blue, serious and modern", 0x1F2A30, 0x455A64, 0xB0BEC5, 0xFFFFFF,
            0x161E22),
        new Palette("citrus", "pale yellow to light orange, cheerful and light", 0xFFF59D, 0xFFCC80, 0xE65100,
            0x212121, 0xFFFDE7),
        new Palette("sakura", "pale pink to pink, gentle and friendly", 0xFCE4EC, 0xF8BBD0, 0xAD1457, 0x3E2723,
            0xFFFFFF),
        new Palette("mint", "pale mint to teal, clean and clear", 0xE0F2F1, 0x80CBC4, 0x00695C, 0x102A27, 0xFFFFFF));

    /** Where the text sits. */
    public record Layout(String name, String description) {}

    public static final List<Layout> LAYOUTS = List.of(
        new Layout("center", "the text centred, the motif around it"),
        new Layout("left", "the text on the left beside a vertical accent bar, the motif on the right"),
        new Layout("band", "the text on a solid band across the middle, the motif above and below"),
        new Layout("corner", "the text in the lower left, the motif filling the upper right"));

    /** The graphic drawn behind the text. */
    public record Motif(String name, String description) {}

    public static final List<Motif> MOTIFS = List.of(
        new Motif("circles", "large soft overlapping circles"),
        new Motif("waves", "flowing waves along the bottom"),
        new Motif("grid", "a fine grid of lines, like graph paper"),
        new Motif("stripes", "bold diagonal stripes"),
        new Motif("dots", "an even pattern of dots"),
        new Motif("rays", "rays fanning out from a corner"),
        new Motif("plain", "no motif, the gradient alone"));

    /** One cover as designed: names from the catalogue and the text to draw. */
    public record Design(String palette, String layout, String motif, String text) {}

    /** The designs kept, in order, and how many were dropped. */
    public record Checked(List<Design> valid, int invalid) {
        public Checked {
            valid = List.copyOf(valid);
        }
    }

    public static Palette palette(String name) {
        return PALETTES.stream().filter(p -> p.name().equals(name)).findFirst().orElse(null);
    }

    public static boolean layout(String name) {
        return LAYOUTS.stream().anyMatch(l -> l.name().equals(name));
    }

    public static boolean motif(String name) {
        return MOTIFS.stream().anyMatch(m -> m.name().equals(name));
    }

    /**
     * Keeps the first {@value #COVERS} designs that name the catalogue and whose text, trimmed, is 1 to
     * {@value #MAX_TEXT} characters without control characters that {@code drawable} accepts; the others are counted.
     */
    public static Checked check(List<Design> raw, Predicate<String> drawable) {
        List<Design> valid = new ArrayList<>();
        int invalid = 0;
        for (Design design : raw == null ? List.<Design>of() : raw) {
            Design cleaned = design == null ? null : clean(design);
            if (cleaned == null || valid.size() >= COVERS || palette(cleaned.palette()) == null
                || !layout(cleaned.layout()) || !motif(cleaned.motif()) || !text(cleaned.text())
                || !drawable.test(cleaned.text())) {
                invalid++;
                continue;
            }
            valid.add(cleaned);
        }
        return new Checked(valid, invalid);
    }

    /** The design with its text trimmed and its inner runs of white space made single spaces. */
    static Design clean(Design design) {
        String text = design.text() == null ? null : design.text().strip().replaceAll("\\s+", " ");
        return new Design(design.palette(), design.layout(), design.motif(), text);
    }

    static boolean text(String text) {
        if (text == null || text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT) {
            return false;
        }
        return text.codePoints().noneMatch(cp -> Character.isISOControl(cp) || Character.getType(cp)
            == Character.FORMAT || Character.getType(cp) == Character.PRIVATE_USE);
    }

    /** The catalogue as the system prompt lists it: each name with what it looks like. */
    static String catalogue() {
        StringBuilder text = new StringBuilder();
        text.append("Palettes:\n");
        PALETTES.forEach(p -> text.append("- ").append(p.name()).append(": ").append(p.description()).append('\n'));
        text.append("Layouts:\n");
        LAYOUTS.forEach(l -> text.append("- ").append(l.name()).append(": ").append(l.description()).append('\n'));
        text.append("Motifs:\n");
        MOTIFS.forEach(m -> text.append("- ").append(m.name()).append(": ").append(m.description()).append('\n'));
        return text.toString();
    }

    /** The design as the stored candidates hold it, with its number and file. */
    static Map<String, Object> stored(int no, Design design, String fileId) {
        Map<String, Object> cover = new LinkedHashMap<>();
        cover.put("no", no);
        cover.put("palette", design.palette());
        cover.put("layout", design.layout());
        cover.put("motif", design.motif());
        cover.put("text", design.text());
        cover.put("fileId", fileId);
        return cover;
    }

    private CoverDesigns() {}
}
