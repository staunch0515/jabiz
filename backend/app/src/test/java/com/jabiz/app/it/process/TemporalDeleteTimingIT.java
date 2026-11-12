package com.jabiz.app.it.process;

import com.jabiz.app.it.fixture.ItTemporalFixtures;
import com.jabiz.context.RequestContext;
import com.jabiz.runtime.DatasetEntityManager;
import com.jabiz.runtime.EntityAction;
import com.jabiz.runtime.EntityChange;
import com.jabiz.runtime.EntityInstance;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How long deleting many instances of a temporal entity takes (ROADMAP 17zz21: QuizBuks replacing a question bank of
 * about 19,000 rows), with the platform's settings. Run on demand only: the environment variable
 * {@code JABIZ_TEST_DELETE_TIMING} gives the number of deletions; the time is printed, not asserted.
 */
@SpringBootTest
class TemporalDeleteTimingIT extends PostgresIntegrationTest {

    private static final RequestContext WRITER = new RequestContext("it-writer", null, Locale.ENGLISH, "it-writer",
        Set.of(), Set.of("*"));

    @Autowired
    DatasetEntityManager entities;

    @Autowired
    DatasetRegistry datasets;

    @Test
    void timingOfManyDeletions() {
        String asked = System.getenv("JABIZ_TEST_DELETE_TIMING");
        Assumptions.assumeTrue(asked != null && !asked.isBlank());
        int total = Integer.parseInt(asked.trim());
        var dataset = datasets.findById(ItTemporalFixtures.NOTE_DATASET).orElseThrow();
        int perCommit = dataset.policy().maxWriteBatchSize();
        List<List<EntityChange>> commits = new ArrayList<>();
        for (int done = 0; done < total; done += perCommit) {
            List<EntityChange> inserts = new ArrayList<>();
            List<EntityChange> deletes = new ArrayList<>();
            for (int i = 0; i < Math.min(perCommit, total - done); i++) {
                String id = UUID.randomUUID().toString();
                inserts.add(new EntityChange(EntityAction.INSERT, new EntityInstance(id, "ItNote", 0, null,
                    Map.of("noteId", id, "body", "timing " + i)), null));
                deletes.add(new EntityChange(EntityAction.DELETE, new EntityInstance(id, "ItNote", 1, null, Map.of()),
                    null));
            }
            asRequest(WRITER, entities.commitBatch(dataset, inserts)).block();
            commits.add(deletes);
        }
        long start = System.nanoTime();
        for (List<EntityChange> deletes : commits) {
            asRequest(WRITER, entities.commitBatch(dataset, deletes)).block();
        }
        System.out.printf("[temporal-delete-timing] %d deletions in %d commits of up to %d: %d ms%n", total,
            commits.size(), perCommit, (System.nanoTime() - start) / 1_000_000);
    }
}
