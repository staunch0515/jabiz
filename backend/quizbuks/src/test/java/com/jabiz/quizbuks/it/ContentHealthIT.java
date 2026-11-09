package com.jabiz.quizbuks.it;

import com.jabiz.job.JobDefinition;
import com.jabiz.quizbuks.QbPermissions;
import com.jabiz.quizbuks.content.ContentHealth;
import com.jabiz.runtime.test.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S6: the content's daily check reports versions whose snapshot is near the size limit; the job asks the platform
 * with a task only when it finds something.
 */
class ContentHealthIT extends PlayItSupport {

    @Autowired
    @Qualifier("qbContentCheckJob")
    JobDefinition<ContentHealth.CheckInput> contentCheckJob;

    private List<Map<String, Object>> health(long nearBytes) {
        return items(ContentHealth.HEALTH_TEMPLATE, TestTokens.bearer(tokens, "fs6-health",
            QbPermissions.ADMIN_QUIZ_READ), Map.of("params", Map.of("nearBytes", nearBytes)));
    }

    private int tasks() {
        return query("SELECT DISTINCT task_id FROM sys_task_version WHERE task_type = ?",
            ContentHealth.CHECK_TASK_TYPE).size();
    }

    @Test
    void versionsNearTheSnapshotLimitAreReported() {
        String token = sponsorToken(newSponsor("s-fs6-health"));
        String quizId = newQuiz(token, "Health");
        run("QB_QUESTION_SAVE", token, body("quizId", quizId, "stem", "One?", "points", 1, "options",
            List.of(body("text", "Yes", "correct", true), body("text", "No"))));
        run("QB_QUIZ_PUBLISH_VERSION", token, body("quizId", quizId));

        // Nothing is near the real limit: no rows, and the daily job leaves no task.
        assertThat(health(ContentHealth.nearBytes())).isEmpty();
        execute("UPDATE jabiz_shedlock SET lock_until = locked_at");
        jobRunner.run(contentCheckJob, clock.instant());
        assertThat(tasks()).isZero();

        // With a threshold of a few bytes every version counts.
        assertThat(health(10)).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("checkCode", "SNAPSHOT_NEAR_LIMIT");
            assertThat(((Number) row.get("found")).intValue()).isPositive();
        });
        // Which versions: the listing names the quiz and the size.
        assertThat(items("qb.admin.large-versions", TestTokens.bearer(tokens, "fs6-health",
            QbPermissions.ADMIN_QUIZ_READ), Map.of("params", Map.of("overBytes", 10)))).anySatisfy(row -> {
                assertThat(row).containsEntry("quizId", quizId);
                assertThat(((Number) row.get("bytes")).intValue()).isGreaterThan(10);
            });
        assertThat(ContentHealth.nearBytes()).isEqualTo(
            (long) com.jabiz.quizbuks.content.ContentLimits.MAX_SNAPSHOT_BYTES * 80 / 100);
    }
}
