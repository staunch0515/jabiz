package com.jabiz.quizbuks.it;

import com.jabiz.dataset.DatasetDefinition;
import com.jabiz.process.ProcessAccess;
import com.jabiz.process.ProcessDefinition;
import com.jabiz.quizbuks.setup.QbRoles;
import com.jabiz.runtime.dataset.DatasetRegistry;
import com.jabiz.runtime.process.ProcessRegistry;
import com.jabiz.runtime.test.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every QuizBuks write a taker or a sponsor can make needs a verified address (docs/quizbuks/plans/
 * Q40-social-sign-in.md, D-Q40-4). Both entries let unverified accounts sign in (the app since Q2, the sponsor console
 * since Q40, so that an account opened through Facebook or LINE can verify in its own session), so this is what keeps
 * an address nobody proved from doing anything. A process either role can start, or a dataset either role can write,
 * without {@code requiresVerifiedEmail()} fails here unless it is listed below with its reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class VerifiedEmailWritesIT extends PostgresIntegrationTest {

    /** Writes an unverified account may make, and why. */
    static final Map<String, String> EXCEPTIONS = Map.of(
        "process QB_ATTEMPT_RESULT", "reads the result of one of the caller's own attempts and writes nothing",
        "process QB_SETTINGS_SAVE", "the caller's own sound switch (Q57): one row of their own, which reaches nobody "
            + "else, so an unverified taker browsing the app can mute it too");

    @Autowired
    ProcessRegistry processes;

    @Autowired
    DatasetRegistry datasets;

    static boolean holds(Set<String> held, Set<String> needed) {
        return !needed.isEmpty() && held.containsAll(needed);
    }

    @Test
    void everyWriteOfATakerOrASponsorNeedsAVerifiedAddress() {
        Set<String> unguarded = new TreeSet<>();
        for (String role : List.of(QbRoles.TAKER, QbRoles.SPONSOR)) {
            Set<String> held = Set.copyOf(QbRoles.of(role).permissions());
            for (ProcessDefinition<?, ?, ?> process : processes.all()) {
                boolean startable = process.access() == ProcessAccess.OPEN && (holds(held, process.permissions())
                    || holds(held, process.alternativePermissions()));
                if (startable && !process.verifiedEmail()) {
                    unguarded.add("process " + process.name());
                }
            }
            for (DatasetDefinition dataset : datasets.all()) {
                String write = dataset.permissions().write();
                boolean writable = write != null && held.contains(write) && !dataset.policy().processOnlyWrites();
                if (writable && !dataset.policy().writeVerifiedEmail()) {
                    unguarded.add("dataset " + dataset.resourceId());
                }
            }
        }
        unguarded.removeAll(EXCEPTIONS.keySet());
        assertThat(unguarded).as("writes an unverified taker or sponsor can make").isEmpty();
    }

    @Test
    void theExceptionsAreStillThere() {
        Set<String> known = new TreeSet<>();
        processes.all().forEach(process -> known.add("process " + process.name()));
        datasets.all().forEach(dataset -> known.add("dataset " + dataset.resourceId()));
        assertThat(known).containsAll(EXCEPTIONS.keySet());
    }
}
