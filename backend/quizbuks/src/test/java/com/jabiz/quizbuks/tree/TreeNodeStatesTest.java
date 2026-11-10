package com.jabiz.quizbuks.tree;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The five states a node shows (F-S3): the server's {@link TreeNodeStates} passes the same cases as the pages'
 * {@code qb/tree/states.ts} ({@code quizbuks-web/app/src/qb/tree/tree-state-cases.json}; the test runs in
 * backend/quizbuks).
 */
class TreeNodeStatesTest {

    private static final Path CASES = Path.of("../../quizbuks-web/app/src/qb/tree/tree-state-cases.json");

    private static List<String> strings(JsonNode node) {
        List<String> out = new ArrayList<>();
        node.forEach(item -> out.add(item.asString()));
        return out;
    }

    @Test
    void everyCaseHasItsState() throws IOException {
        JsonMapper mapper = JsonMapper.builder().build();
        JsonNode cases = mapper.readTree(Files.readString(CASES)).get("cases");
        assertThat(cases.size()).isGreaterThan(5);
        Set<String> seen = new LinkedHashSet<>();
        cases.forEach(row -> {
            TreeCondition condition = TreeCondition.parse(mapper.convertValue(row.get("condition"), Map.class));
            TreeNodeStates.State state = TreeNodeStates.of(new TreeNodeStates.Facts(row.get("lit").asBoolean(),
                strings(row.get("unmet")), row.get("startable").asBoolean(), condition,
                Set.copyOf(strings(row.get("litRefs")))));
            assertThat(state.name()).as(row.get("name").asString()).isEqualTo(row.get("state").asString());
            seen.add(state.name());
        });
        // Every state is covered by a case.
        assertThat(seen).containsExactlyInAnyOrder("LIT", "LOCKED", "CAN_ANSWER", "WAITING", "MISSING");
    }
}
