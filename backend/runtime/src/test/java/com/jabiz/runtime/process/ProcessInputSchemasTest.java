package com.jabiz.runtime.process;

import com.jabiz.security.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link ProcessInputSchemas}: the JSON Schema clients generate process forms from (decision D15). */
class ProcessInputSchemasTest {

    enum Direction { DEBIT, CREDIT }

    record Line(@NotBlank String accountCode, @NotNull Direction direction, @NotNull @Positive BigDecimal amount) {}

    record Post(Instant bookingTime, @NotBlank @Size(max = 200) String description, String reference,
        @NotEmpty @Size(max = 10) List<@Valid @NotNull Line> entries, @Sensitive String secret, UUID id,
        LocalDate day, @Min(1) @Max(9) int count, @PositiveOrZero long total, boolean flag, Double ratio,
        Set<String> tags, Map<String, Object> extra, Object anything, @Email String mail,
        @Pattern(regexp = "\\d{4}-\\d{2}") String month, @Pattern(regexp = "(?<=a)b") String javaOnly,
        String[] names) {}

    record Node(String name, Node child) {}

    record Odd(Thread thread, List<? extends Number> numbers) {}

    @Test
    @SuppressWarnings("unchecked")
    void recordsBecomeObjectSchemasWithTheirConstraints() {
        ProcessInputSchemas.Result result = ProcessInputSchemas.of(Post.class);
        assertThat(result.problems()).isEmpty();
        Map<String, Object> schema = result.schema();
        assertThat(schema).containsEntry("$schema", "https://json-schema.org/draft/2020-12/schema")
            .containsEntry("type", "object").containsEntry("title", "Post")
            .containsEntry("required", List.of("description", "entries"))
            .containsEntry("additionalProperties", false);
        Map<String, Map<String, Object>> p = (Map<String, Map<String, Object>>) schema.get("properties");
        assertThat(p.keySet()).startsWith("bookingTime", "description", "reference", "entries");
        assertThat(p.get("bookingTime")).isEqualTo(Map.of("type", "string", "format", "date-time"));
        assertThat(p.get("description")).containsEntry("minLength", 1).containsEntry("maxLength", 200)
            .containsEntry("pattern", "\\S");
        assertThat(p.get("entries")).containsEntry("type", "array").containsEntry("minItems", 1)
            .containsEntry("maxItems", 10);
        Map<String, Object> line = (Map<String, Object>) p.get("entries").get("items");
        assertThat(line).containsEntry("required", List.of("accountCode", "direction", "amount"));
        Map<String, Map<String, Object>> lp = (Map<String, Map<String, Object>>) line.get("properties");
        assertThat(lp.get("direction")).containsEntry("enum", List.of("DEBIT", "CREDIT"));
        assertThat(lp.get("amount")).containsEntry("format", "decimal").containsEntry("exclusiveMinimum", 0)
            .containsEntry("type", List.of("string", "number"));
        assertThat(p.get("secret")).containsEntry("writeOnly", true).containsEntry("format", "password");
        assertThat(p.get("id")).containsEntry("format", "uuid");
        assertThat(p.get("day")).containsEntry("format", "date");
        assertThat(p.get("count")).containsEntry("type", "integer").containsEntry("minimum", 1L)
            .containsEntry("maximum", 9L);
        assertThat(p.get("total")).containsEntry("minimum", 0);
        assertThat(p.get("flag")).containsEntry("type", "boolean");
        assertThat(p.get("ratio")).containsEntry("type", "number");
        assertThat(p.get("tags")).containsEntry("type", "array").containsEntry("items", Map.of("type", "string"));
        assertThat(p.get("extra")).containsEntry("type", "object");
        assertThat(p.get("anything")).isEmpty();
        assertThat(p.get("mail")).containsEntry("format", "email");
        assertThat(p.get("month")).containsEntry("pattern", "^(?:\\d{4}-\\d{2})$");
        // A pattern JavaScript would read differently is left to the server.
        assertThat(p.get("javaOnly")).doesNotContainKey("pattern");
        assertThat(p.get("names")).containsEntry("items", Map.of("type", "string"));
    }

    @Test
    void whatTheSchemaCannotDescribeIsReported() {
        assertThat(ProcessInputSchemas.of(Node.class).problems()).containsExactly("$.child: recursive type "
            + Node.class.getName());
        assertThat(ProcessInputSchemas.of(Odd.class).problems())
            .containsExactly("$.thread: java.lang.Thread", "$.numbers[]: ? extends java.lang.Number");
    }
}
