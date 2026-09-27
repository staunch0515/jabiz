package com.jabiz.entity.i18n;

import com.jabiz.context.RequestContext;
import com.jabiz.entity.CustomKinds;
import com.jabiz.entity.EntityDefinition;
import com.jabiz.entity.EntityValidator;
import com.jabiz.entity.JsonSchemaExporter;
import com.jabiz.entity.KindViolation;
import com.jabiz.entity.MetaModelExporter;
import com.jabiz.entity.SemanticKind;
import com.jabiz.entity.SemanticKinds;
import com.jabiz.entity.ValidationContext;
import com.jabiz.entity.Violation;
import com.jabiz.i18n.MessageCatalog;
import com.jabiz.i18n.PlatformLanguages;
import com.jabiz.query.QueryOperator;
import com.jabiz.testkinds.TestI18nText;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;
import static org.assertj.core.api.Assertions.tuple;

/** The multilingual text kind (docs/design/16-content-authoring.md section 1, decision D20). */
class I18nTextTest {

    static {
        TestI18nText.register();
    }

    private static final ValidationContext CTX = new ValidationContext(
        Clock.fixed(Instant.parse("2026-01-31T09:00:00Z"), ZoneOffset.UTC), RequestContext.system(Locale.ENGLISH, "t"));

    private static final EntityDefinition ARTICLE = EntityDefinition.define("Article", eb -> {
        eb.physicalTable("t_article");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id").asSemanticIdentity("urn:test:article"));
        eb.field("title", f -> f.physicalColumn("f_title").required(true).apply(I18nText.of(5).required("en")));
        eb.field("body", f -> f.physicalColumn("f_body").apply(I18nText.markdown(100).required("zh", "en")));
        eb.display("title");
    });

    private final I18nTextSupport support = (I18nTextSupport) CustomKinds.require(I18nText.KIND_ID);

    private static Map<String, Object> params(String field) {
        return ((SemanticKind.Custom) ARTICLE.field(field).kind()).params();
    }

    private static List<Violation> check(Map<String, Object> raw, boolean insert) {
        return EntityValidator.check(ARTICLE, raw, CTX, insert).violations();
    }

    @Test
    void declarationsBecomeCustomKindParameters() {
        assertThat(I18nText.of(200).kind()).isEqualTo(new SemanticKind.Custom(I18nText.KIND_ID,
            Map.of("maxLength", 200, "multiline", false, "format", "plain", "requiredLanguages", List.of())));
        // Required languages are kept in platform order whatever order the declaration lists them in.
        assertThat(I18nText.of(10).multiline().required("en", "zh").kind().params())
            .containsEntry("multiline", true).containsEntry("format", "plain")
            .containsEntry("requiredLanguages", List.of("zh", "en"));
        assertThat(I18nText.markdown(10).kind().params())
            .containsEntry("multiline", true).containsEntry("format", "markdown");
        assertThat(I18nText.is(I18nText.of(1).kind())).isTrue();
        assertThat(I18nText.is(new SemanticKind.Text(1, false))).isFalse();
    }

    @Test
    void declarationsRejectUnknownLanguagesAndNonPositiveLengths() {
        assertThatThrownBy(() -> I18nText.of(10).required("fr"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'fr' is not a supported language");
        assertThatThrownBy(() -> I18nText.of(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputKeepsSupportedLanguagesInPlatformOrderWithoutBlankTexts() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("en", "Hello");
        raw.put("ja", "  ");
        raw.put("zh", "你好");
        raw.put("fr", null);
        assertThatThrownBy(() -> support.coerce(params("title"), raw, true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'fr' is not a supported language");

        raw.remove("fr");
        Object value = support.coerce(params("title"), raw, true);
        assertThat(value).isEqualTo(Map.of("zh", "你好", "en", "Hello"));
        assertThat(List.<Object>copyOf(((Map<?, ?>) value).keySet())).containsExactly("zh", "en");
    }

    @Test
    void noTextAtAllIsNull() {
        assertThat(support.coerce(params("title"), Map.of("en", " ", "zh", ""), true)).isNull();
        assertThat(support.coerce(params("title"), Map.of(), true)).isNull();
    }

    @Test
    void storedJsonIsParsedAndTakenAsItIs() {
        assertThat(support.coerce(params("title"), "{\"fr\":\"Bonjour\",\"en\":\" \"}", false))
            .isEqualTo(Map.of("fr", "Bonjour", "en", " "));
        assertThatThrownBy(() -> support.coerce(params("title"), "[1]", false))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void valuesMustBeObjectsOfTexts() {
        assertThatThrownBy(() -> support.coerce(params("title"), 42, true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Integer");
        assertThatThrownBy(() -> support.coerce(params("title"), "{\"en\":\"Hi\"}", true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("String");
        assertThatThrownBy(() -> support.coerce(params("title"), Map.of("en", 1), true))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("'en' is not a text");
    }

    @Test
    void tooLongTextsComeFirstThenMissingLanguages() {
        List<KindViolation> violations = support.validate(params("body"),
            Map.of("ja", "x".repeat(101), "en", "y".repeat(101)));
        assertThat(violations).extracting(KindViolation::code, KindViolation::params).containsExactly(
            tuple("TOO_LONG", Map.of("lang", "ja", "max", 100)),
            tuple("TOO_LONG", Map.of("lang", "en", "max", 100)),
            tuple("TRANSLATION_REQUIRED", Map.of("lang", "zh")));
    }

    @Test
    void lengthCountsCodePoints() {
        assertThat(support.validate(params("title"), Map.of("en", "😀".repeat(5)))).isEmpty();
        assertThat(support.validate(params("title"), Map.of("en", "😀".repeat(6))))
            .extracting(KindViolation::code).containsExactly("TOO_LONG");
    }

    @Test
    void theValidatorReportsTheFirstConstraintWithTheField() {
        assertThat(check(Map.of("title", Map.of("zh", "太长的标题文字")), false))
            .extracting(Violation::field, Violation::ruleCode, Violation::params)
            .containsExactly(tuple("title", "TOO_LONG", Map.of("lang", "zh", "max", 5)));
        assertThat(check(Map.of("title", Map.of("zh", "标题")), false))
            .extracting(Violation::ruleCode, Violation::params)
            .containsExactly(tuple("TRANSLATION_REQUIRED", Map.of("lang", "en")));
        assertThat(check(Map.of("title", Map.of("de", "Titel")), false))
            .extracting(Violation::ruleCode).containsExactly("INVALID_VALUE");
        assertThat(check(Map.of("title", Map.of("en", "Title")), false)).isEmpty();
    }

    @Test
    void onlyBlankTextsCountAsMissing() {
        assertThat(check(Map.of("title", Map.of("en", "  ")), true))
            .extracting(Violation::field, Violation::ruleCode).containsExactly(tuple("title", "REQUIRED"));
    }

    @Test
    void onlyNullChecksAreAllowed() {
        assertThat(SemanticKinds.allowedOperators(ARTICLE.field("title").kind()))
            .containsExactlyInAnyOrder(QueryOperator.IS_NULL, QueryOperator.IS_NOT_NULL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExportDescribesTheEditorAndTheLanguages() {
        Map<String, Object> exported = MetaModelExporter.export(ARTICLE);
        assertThat(exported).containsEntry("display", "title");
        Map<String, Object> body = ((List<Map<String, Object>>) exported.get("fields")).get(2);
        // The kind's parameters never shadow the field's own flags.
        assertThat((Boolean) ((List<Map<String, Object>>) exported.get("fields")).get(1).get("required")).isTrue();
        assertThat(body).containsEntry("required", false);
        assertThat(body).contains(entry("type", "custom"), entry("kindId", "jabiz.i18n-text"),
            entry("format", "markdown"), entry("multiline", true), entry("maxLength", 100),
            entry("requiredLanguages", List.of("zh", "en")), entry("locales", PlatformLanguages.CODES),
            entry("operators", List.of("IS_NOT_NULL", "IS_NULL")));

        Map<String, Object> schema = (Map<String, Object>) ((Map<String, Object>) JsonSchemaExporter.export(ARTICLE)
            .get("properties")).get("title");
        assertThat(((Map<String, Object>) schema.get("x-jabiz-kind"))).containsEntry("kindId", "jabiz.i18n-text");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theExportCarriesTheMessagesOfTheKindsCodes() {
        MessageCatalog catalog = new MessageCatalog(List.of(MessageCatalog.PLATFORM_BUNDLE), PlatformLanguages.LOCALES,
            Locale.ENGLISH, getClass().getClassLoader());
        Map<String, Object> messages = (Map<String, Object>) MetaModelExporter.export(ARTICLE, catalog, Locale.ENGLISH)
            .get("messages");
        assertThat(messages).containsKeys("TOO_LONG", "TRANSLATION_REQUIRED");
        assertThat((String) messages.get("TRANSLATION_REQUIRED")).contains("{lang}");
    }
}
