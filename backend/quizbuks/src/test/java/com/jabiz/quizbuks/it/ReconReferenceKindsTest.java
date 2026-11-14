package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.wallet.ReconChecks;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-M14b: the reconciliation page makes an item's reference a link by its check (`REFERENCE_KINDS` in the admin
 * extension's `ids.ts`). A check renamed or removed on the server would leave its items without their link, quietly:
 * every check the page names is one the server reports.
 */
class ReconReferenceKindsTest {

    static final Path IDS = Path.of("admin-extension/src/ids.ts");

    @Test
    void everyCheckWhoseReferenceIsALinkIsAServerCheck() throws IOException {
        String ids = Files.readString(IDS);
        int start = ids.indexOf("const REFERENCE_KINDS");
        assertThat(start).as("REFERENCE_KINDS in ids.ts").isNotNegative();
        String block = ids.substring(start, ids.indexOf("\n}", start));
        List<String> checks = Pattern.compile("\\n\\s+([A-Z][A-Z0-9_]+): '").matcher(block).results()
            .map(found -> found.group(1)).toList();
        assertThat(checks).isNotEmpty();
        assertThat(ReconChecks.ALL).containsAll(checks);
    }
}
