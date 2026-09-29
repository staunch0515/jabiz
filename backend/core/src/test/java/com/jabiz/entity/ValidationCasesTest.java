package com.jabiz.entity;

import com.jabiz.context.RequestContext;
import com.jabiz.dictionary.DictionaryLookup;
import com.jabiz.file.FileKind;
import com.jabiz.entity.i18n.I18nText;
import com.jabiz.testkinds.TestI18nText;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The validation cases shared with the frontend (decision D15): the frontend's validator reads the same file and
 * must report the same rule codes. This test proves the server side, and that the field metadata in the file is
 * exactly what {@link MetaModelExporter} exports for {@link #SAMPLE}; the frontend therefore validates against real
 * exported metadata.
 */
class ValidationCasesTest {

    /** Exercises every exportable rule kind and every kind constraint the client repeats (multilingual text: D20). */
    static {
        TestI18nText.register();
    }

    static final EntityDefinition SAMPLE = EntityDefinition.define("ValidationSample", eb -> {
        eb.physicalTable("t_validation_sample");
        eb.primaryKey("id");
        eb.field("id", f -> f.physicalColumn("f_id").required(true).generated(true).asSemanticIdentity("urn:sample"));
        eb.field("title", f -> f.physicalColumn("f_title").required(true).asText(10)
            .apply(Rules.notBlank("TITLE_BLANK")));
        eb.field("code", f -> f.physicalColumn("f_code").asText(20)
            .apply(Rules.pattern("CODE_FORMAT", "[A-Z]{2}-[0-9]{3}")));
        eb.field("nickname", f -> f.physicalColumn("f_nickname").asText(null, false)
            .apply(Rules.length("NICK_LENGTH", 2, 5)));
        eb.field("amount", f -> f.physicalColumn("f_amount").required(true).asMonetary("JPY", 2)
            .apply(Rules.range("AMOUNT_RANGE", BigDecimal.ZERO, new BigDecimal("1000000")))
            .apply(Rules.scale("AMOUNT_SCALE", 2)));
        // A monetary field without its own SCALE rule: the currency's scale is checked (MONETARY_SCALE).
        eb.field("fee", f -> f.physicalColumn("f_fee").asMonetary("USD", 2));
        eb.field("ratio", f -> f.physicalColumn("f_ratio").asNumeric(5, 2)
            .apply(Rules.range("RATIO_MAX", null, new BigDecimal("100"))));
        eb.field("happenedAt", f -> f.physicalColumn("f_happened").asTemporal(TemporalRole.EVENT_TIME)
            .apply(Rules.notFuture("NOT_IN_FUTURE", 60)));
        eb.field("active", f -> f.physicalColumn("f_active").asBool());
        eb.field("status", f -> f.physicalColumn("f_status").asCode("urn:sample:status", "OPEN", "DONE"));
        eb.field("port", f -> f.physicalColumn("f_port").asCode("urn:sample:port"));
        eb.field("attachment", f -> f.physicalColumn("f_attachment").kind(FileKind.of("sample.document")));
        eb.field("headline", f -> f.physicalColumn("f_headline").required(true).apply(I18nText.of(8)));
        eb.field("summary", f -> f.physicalColumn("f_summary").apply(I18nText.markdown(10).required("en")));
    });

    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static Path file() {
        String path = System.getProperty("validation-cases.file");
        return Path.of(path != null ? path : "../../spec/validation-cases.json");
    }

    private static JsonNode read() throws IOException {
        return JSON.readTree(Files.readString(file(), StandardCharsets.UTF_8));
    }

    @SuppressWarnings("unchecked")
    private static JsonNode exportedFields() {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) MetaModelExporter.export(SAMPLE).get("fields");
        // Through text, so numbers compare by their JSON form rather than their Java type.
        return JSON.readTree(JSON.writeValueAsString(fields));
    }

    @Test
    void fieldMetadataIsWhatTheServerExports() throws IOException {
        JsonNode cases = read();
        if (Boolean.getBoolean("validation-cases.update")) {
            ((ObjectNode) cases).set("fields", exportedFields());
            Files.writeString(file(), JSON.writeValueAsString(cases) + "\n", StandardCharsets.UTF_8);
            cases = read();
        }
        assertThat(cases.get("fields"))
            .as("fields in %s differ from the export; run ./gradlew :core:test -Dvalidation-cases.update=true", file())
            .isEqualTo(exportedFields());
    }

    @TestFactory
    Stream<DynamicTest> serverReportsTheExpectedCodes() throws IOException {
        JsonNode root = read();
        Instant now = Instant.parse(root.get("now").asString());
        ValidationContext ctx = new ValidationContext(Clock.fixed(now, ZoneOffset.UTC),
            RequestContext.system(Locale.ENGLISH, "cases"));
        Map<String, Set<String>> dictionaries = new LinkedHashMap<>();
        root.get("dictionaries").properties().forEach(e -> {
            Set<String> codes = new HashSet<>();
            e.getValue().forEach(code -> codes.add(code.asString()));
            dictionaries.put(e.getKey(), codes);
        });
        DictionaryLookup lookup = urn -> Optional.ofNullable(dictionaries.get(urn));

        List<DynamicTest> tests = new ArrayList<>();
        int index = 0;
        for (JsonNode c : root.get("cases")) {
            String field = c.get("field").asString();
            boolean insert = c.has("insert") && c.get("insert").asBoolean();
            List<String> expected = new ArrayList<>();
            c.get("expect").forEach(code -> expected.add(code.asString()));
            Map<String, Object> raw = new LinkedHashMap<>();
            if (c.has("value")) {
                // Converted the way the web layer converts a JSON body into Map<String, Object>.
                raw.put(field, JSON.treeToValue(c.get("value"), Object.class));
            }
            String name = "#" + (index++) + " " + field + " = " + (c.has("value") ? c.get("value") : "(absent)")
                          + (insert ? " (insert)" : "");
            tests.add(DynamicTest.dynamicTest(name, () -> {
                List<String> actual = EntityValidator.check(SAMPLE, raw, ctx, insert, lookup).violations().stream()
                    .filter(v -> field.equals(v.field()))
                    .map(Violation::ruleCode)
                    .toList();
                assertThat(actual).isEqualTo(expected);
            }));
        }
        return tests.stream();
    }
}
