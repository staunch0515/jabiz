package com.jabiz.quizbuks.it;

import com.jabiz.quizbuks.help.HelpProcesses;
import com.jabiz.quizbuks.help.QbHelp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-C10 (docs/quizbuks/plans/impl/F-C10.md; platform decision D62): erasing an account deletes its AI help
 * conversations, their messages and its usage rows; what the user asked, what they handed to a person and what the AI
 * answered them are found in no table afterwards (the job's input and result are the platform's to purge). Everybody's
 * usage stays: it holds no person.
 */
class HelpErasureIT extends HelpItSupport {

    @DynamicPropertySource
    static void maintenance(DynamicPropertyRegistry registry) {
        // The tests' login is the maintenance login too (a member of jabiz_maintenance, granted below), for the purge.
        registry.add("jabiz.erasure.datasource.url", () -> DB.r2dbcUrl() + "?schema=" + schema());
        registry.add("jabiz.erasure.datasource.username", DB::username);
        registry.add("jabiz.erasure.datasource.password", DB::password);
    }

    @BeforeEach
    void maintenanceRole() {
        execute("DO $$ BEGIN CREATE ROLE jabiz_maintenance; EXCEPTION WHEN duplicate_object THEN NULL; END $$");
        execute("DO $$ BEGIN EXECUTE 'GRANT jabiz_maintenance TO ' || quote_ident(current_user);"
            + " EXCEPTION WHEN OTHERS THEN NULL; END $$");
        helpOn();
    }

    @Test
    void anErasedAccountsHelpGoesEverywhere() {
        String marker = letters();
        String question = "How do I change my wallet " + marker + "?";
        String answer = "Open the wallet page " + marker + " and follow the steps.";
        String handed = "Please call me back about " + marker + ".";
        String taker = newTaker("t-fc10-erased-" + UUID.randomUUID(), "JP");
        CLAUDE.script(MESSAGES, helpAnswer(answer, List.of("/help/wallet"), false));
        Map<String, Object> asked = ask(taker(taker), null, QbHelp.APP, question, "wallet");
        runJobs();
        String threadId = (String) asked.get("threadId");
        escalate(taker(taker), threadId, QbHelp.APP, handed);
        run(HelpProcesses.REPLY, hqHelp(), body("threadId", threadId, "text", "We will look into it.", "close", false));
        deliverEvents();
        assertThat(helpUsage(taker)).isNotEmpty();
        Map<String, Object> everybody = helpUsage(QbHelp.EVERYBODY);

        assertThat(occurrences(marker)).as("the texts before the erasure").isNotEmpty();
        runAsSystem("ACCOUNT_ERASE", Map.of("userId", taker, "requestedBy", "it-support"));
        deliverEvents();

        assertThat(query("SELECT 1 FROM qb_help_thread WHERE user_id = ?", taker)).isEmpty();
        assertThat(query("SELECT 1 FROM qb_help_message WHERE user_id = ? OR thread_id = ?", taker, threadId))
            .isEmpty();
        assertThat(query("SELECT 1 FROM qb_help_usage WHERE user_id = ?", taker)).isEmpty();
        assertThat(helpUsage(QbHelp.EVERYBODY)).isEqualTo(everybody);
        // The marker is in the question, the words handed over and the answer: none of them is anywhere now.
        assertThat(occurrences(marker)).as("where the help's texts still are").isEmpty();
    }

    /** Letters only: a random UUID now and then holds a run of digits a text check may take for a number. */
    private static String letters() {
        StringBuilder out = new StringBuilder();
        for (char c : UUID.randomUUID().toString().replace("-", "").toCharArray()) {
            out.append(Character.isDigit(c) ? (char) ('g' + (c - '0')) : c);
        }
        return out.toString();
    }

    /** Every text or JSON column of the schema that holds the value, as {@code table.column}. */
    private static List<String> occurrences(String needle) {
        List<String> found = new ArrayList<>();
        for (Map<String, Object> column : query("SELECT table_name::text AS t, column_name::text AS c"
            + " FROM information_schema.columns WHERE table_schema = current_schema()"
            + " AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json')")) {
            String sql = "SELECT count(*) AS n FROM \"" + column.get("t") + "\" WHERE \"" + column.get("c")
                + "\"::text ILIKE ?";
            if (((Number) query(sql, "%" + needle + "%").getFirst().get("n")).longValue() > 0) {
                found.add(column.get("t") + "." + column.get("c"));
            }
        }
        return found;
    }
}
