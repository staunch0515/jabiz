package com.jabiz.quizbuks.page;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rule of a sponsor page's link name (F-F9): the server's {@link QbSponsorPages#HANDLE_PATTERN}, matched as a whole
 * as the {@code PATTERN} rule does, passes the same cases as the console's (`quizbuks-web/sponsor/src/page/
 * handle-cases.json`, also read by `handle.test.ts`; the test runs in backend/quizbuks).
 */
class QbSponsorPagesHandleTest {

    private static final Path CASES = Path.of("../../quizbuks-web/sponsor/src/page/handle-cases.json");

    private static List<String> cases(String kind) throws IOException {
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(CASES));
        List<String> out = new ArrayList<>();
        root.get(kind).forEach(node -> out.add(node.asString()));
        return out;
    }

    @Test
    void theServersRulePassesTheConsolesCases() throws IOException {
        Pattern handle = Pattern.compile(QbSponsorPages.HANDLE_PATTERN);
        List<String> valid = cases("valid");
        List<String> invalid = cases("invalid");
        assertThat(valid).isNotEmpty().allSatisfy(name -> assertThat(handle.matcher(name).matches()).as(name).isTrue());
        assertThat(invalid).isNotEmpty()
            .allSatisfy(name -> assertThat(handle.matcher(name).matches()).as(name).isFalse());
        // The longest valid case is the column's length.
        assertThat(valid.stream().mapToInt(String::length).max().orElseThrow()).isEqualTo(QbSponsorPages.HANDLE_LENGTH);
    }
}
