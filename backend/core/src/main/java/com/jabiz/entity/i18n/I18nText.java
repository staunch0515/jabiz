package com.jabiz.entity.i18n;

import com.jabiz.entity.FieldBuilder;
import com.jabiz.entity.SemanticKind;
import com.jabiz.i18n.PlatformLanguages;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Declaration of a {@value #KIND_ID} field: content in several languages, {@code {"zh": "...", "en": "..."}},
 * stored as JSON (docs/design/16-content-authoring.md section 1). Applied like the {@link com.jabiz.entity.Rules}:
 *
 * <pre>
 * eb.field("title", f -&gt; f.physicalColumn("title").apply(I18nText.of(200).required("en")));
 * </pre>
 *
 * Instances are immutable; every method returns a new declaration.
 */
public final class I18nText implements Consumer<FieldBuilder> {

    public static final String KIND_ID = "jabiz.i18n-text";

    static final String MAX_LENGTH = "maxLength";
    static final String MULTILINE = "multiline";
    static final String FORMAT = "format";
    static final String REQUIRED = "required";
    static final String PLAIN = "plain";
    static final String MARKDOWN = "markdown";

    private final int maxLength;
    private final boolean multiline;
    private final String format;
    private final List<String> required;

    private I18nText(int maxLength, boolean multiline, String format, List<String> required) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        for (String language : required) {
            if (!PlatformLanguages.isSupported(language)) {
                throw new IllegalArgumentException("'" + language + "' is not a supported language "
                    + PlatformLanguages.CODES);
            }
        }
        this.maxLength = maxLength;
        this.multiline = multiline;
        this.format = format;
        // In platform order, so exports and validation order do not depend on how the declaration lists them.
        this.required = PlatformLanguages.CODES.stream().filter(required::contains).toList();
    }

    /** Single-line plain text of at most {@code maxLength} code points per language. */
    public static I18nText of(int maxLength) {
        return new I18nText(maxLength, false, PLAIN, List.of());
    }

    /** Markdown (multi-line) of at most {@code maxLength} code points per language; rendered by clients only. */
    public static I18nText markdown(int maxLength) {
        return new I18nText(maxLength, true, MARKDOWN, List.of());
    }

    /** Offers a multi-line editor for plain text. */
    public I18nText multiline() {
        return new I18nText(maxLength, true, format, required);
    }

    /** Languages that must have a text ({@code TRANSLATION_REQUIRED} otherwise). */
    public I18nText required(String... languages) {
        return new I18nText(maxLength, multiline, format, Arrays.asList(languages));
    }

    /** Whether {@code kind} is a multilingual text. */
    public static boolean is(SemanticKind kind) {
        return kind instanceof SemanticKind.Custom c && KIND_ID.equals(c.kindId());
    }

    /** The semantic kind this declaration stands for. */
    public SemanticKind.Custom kind() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(MAX_LENGTH, maxLength);
        params.put(MULTILINE, multiline);
        params.put(FORMAT, format);
        params.put(REQUIRED, required);
        return new SemanticKind.Custom(KIND_ID, params);
    }

    @Override
    public void accept(FieldBuilder field) {
        field.kind(kind());
    }
}
