package com.jabiz.quizbuks.help;

import com.jabiz.quizbuks.messaging.QbLegal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * The text of one entry's and language's materials snapshot (F-C10b): the published manual, the published FAQ of the
 * entry's audience and both, and the legal texts in effect of the entry (Takers: terms of use, privacy policy, contest
 * rules, AI notice; Sponsors: Sponsor terms, privacy policy, the commerce notice, AI notice), as Markdown in that language; a text missing
 * in it is given in English (else Japanese, else Chinese), marked so. The same sources give the same bytes (nothing of
 * the time it was built is in it), so an unchanged snapshot keeps its hash, its file and the provider's prompt cache.
 */
public final class HelpMaterialsText {

    /** One published question of the help centre. */
    public record Faq(String audience, long seq, Map<?, ?> question, Map<?, ?> answer) {}

    /** The version in effect of one legal text. */
    public record Legal(String kind, int number, Instant effectiveAt, Map<?, ?> title, Map<?, ?> body) {}

    /** The languages a text missing in the asked one is given in, in order. */
    static final List<String> FALLBACK = List.of("en", "ja", "zh");

    /** The legal texts of each entry, in order. */
    static List<String> legalKinds(String entry) {
        return QbHelp.SPONSOR.equals(entry)
            ? List.of(QbLegal.TERMS_SPONSOR, QbLegal.PRIVACY, QbLegal.COMMERCE_NOTICE, QbLegal.AI_NOTICE)
            : List.of(QbLegal.TERMS_APP, QbLegal.PRIVACY, QbLegal.CONTEST_RULES, QbLegal.AI_NOTICE);
    }

    /** The FAQ audience of an entry (and {@code ALL}). */
    static String audience(String entry) {
        return QbHelp.SPONSOR.equals(entry) ? QbLegal.AUDIENCE_SPONSOR : QbLegal.AUDIENCE_APP;
    }

    /** The site path of an entry's pages: the help centre and the legal texts are under it. */
    static String base(String entry) {
        return QbHelp.SPONSOR.equals(entry) ? "/sponsor" : "/taker";
    }

    private static final Map<String, String> LEGAL_SLUGS = Map.of(QbLegal.TERMS_APP, "terms",
        QbLegal.TERMS_SPONSOR, "sponsor-terms", QbLegal.PRIVACY, "privacy", QbLegal.CONTEST_RULES, "contest-rules",
        QbLegal.COMMERCE_NOTICE, "commerce", QbLegal.AI_NOTICE, "ai");

    private static final Map<String, String> HEADINGS_EN = Map.of("title", "Help materials", "manual", "User manual",
        "faq", "Frequently asked questions", "legal", "Terms and policies", "fallback", "(not available in this "
            + "language; given in %s)", "version", "version %d, in effect since %s");

    private HelpMaterialsText() {}

    /**
     * The snapshot of {@code entry} in {@code language}.
     *
     * @param manual the published manual of the entry by language; null or empty when it has none yet
     * @param faqs   the published questions (any audience: those of the entry are kept)
     * @param legal  the versions in effect (any kind: those of the entry are kept)
     */
    public static String build(String entry, String language, Map<?, ?> manual, List<Faq> faqs, List<Legal> legal) {
        StringBuilder text = new StringBuilder();
        String who = QbHelp.SPONSOR.equals(entry) ? "Sponsors" : "Takers";
        text.append("# ").append(HEADINGS_EN.get("title")).append(" for ").append(who).append(" (")
            .append(language).append(")\n\n");
        text.append("## ").append(HEADINGS_EN.get("manual")).append("\n\n");
        Picked manualText = pick(manual, language);
        if (manualText == null) {
            text.append("(No manual has been published yet.)\n\n");
        } else {
            note(text, manualText, language);
            text.append(clean(manualText.text())).append("\n\n");
        }
        text.append("## ").append(HEADINGS_EN.get("faq")).append(" (").append(base(entry)).append("/help)\n\n");
        String audience = audience(entry);
        List<Faq> kept = new ArrayList<>(faqs == null ? List.of() : faqs);
        kept.removeIf(faq -> !audience.equals(faq.audience()) && !QbLegal.AUDIENCE_ALL.equals(faq.audience()));
        kept.sort(Comparator.comparingLong(Faq::seq));
        if (kept.isEmpty()) {
            text.append("(No questions have been published yet.)\n\n");
        }
        for (Faq faq : kept) {
            Picked question = pick(faq.question(), language);
            Picked answer = pick(faq.answer(), language);
            if (question == null || answer == null) {
                continue;
            }
            text.append("### ").append(oneLine(question.text())).append("\n\n");
            note(text, answer, language);
            text.append(clean(answer.text())).append("\n\n");
        }
        text.append("## ").append(HEADINGS_EN.get("legal")).append("\n\n");
        for (String kind : legalKinds(entry)) {
            Legal version = (legal == null ? List.<Legal>of() : legal).stream()
                .filter(l -> kind.equals(l.kind())).findFirst().orElse(null);
            String path = base(entry) + "/legal/" + LEGAL_SLUGS.get(kind);
            if (version == null) {
                text.append("### ").append(kind).append(" (").append(path).append(")\n\n(Not published yet.)\n\n");
                continue;
            }
            Picked title = pick(version.title(), language);
            Picked body = pick(version.body(), language);
            text.append("### ").append(title == null ? kind : oneLine(title.text())).append(" (").append(path)
                .append(", ").append(HEADINGS_EN.get("version").formatted(version.number(),
                    version.effectiveAt().atOffset(ZoneOffset.UTC).toLocalDate())).append(")\n\n");
            if (body != null) {
                note(text, body, language);
                text.append(clean(body.text())).append("\n\n");
            }
        }
        return text.toString().stripTrailing() + "\n";
    }

    /** The UTF-8 bytes of a snapshot. */
    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The snapshot's hash: SHA-256 of its bytes, in hex. */
    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes(text)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Picked(String language, String text) {}

    /** The text in the language, else the first of {@link #FALLBACK} there is; null when there is none. */
    private static Picked pick(Map<?, ?> texts, String language) {
        if (texts == null) {
            return null;
        }
        List<String> order = new ArrayList<>();
        order.add(language);
        order.addAll(FALLBACK);
        for (String candidate : order) {
            Object value = texts.get(candidate);
            if (value instanceof String s && !s.isBlank()) {
                return new Picked(candidate, s);
            }
        }
        return null;
    }

    private static void note(StringBuilder text, Picked picked, String language) {
        if (!picked.language().equals(language)) {
            text.append(HEADINGS_EN.get("fallback").formatted(HelpPrompt.LANGUAGES.getOrDefault(picked.language(),
                picked.language()))).append("\n\n");
        }
    }

    /**
     * Text as a snapshot holds it: line ends as {@code \n}, no control character but tabs (a text file the platform
     * recognises as text, decision D18), and no line that could close the tags the prompt puts the materials in.
     */
    static String clean(String value) {
        String text = value.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c < 0x20 && c != '\n' && c != '\t' || c == 0x7F ? ' ' : c);
        }
        return out.toString().replace("</help_materials", "<\\/help_materials").strip();
    }

    private static String oneLine(String value) {
        return clean(value).replace('\n', ' ');
    }
}
