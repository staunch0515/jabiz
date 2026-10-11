package com.jabiz.job;

import com.jabiz.process.ProcessContext;
import com.jabiz.process.ProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobDefinitionTest {

    record In(Instant at) {}

    static final ProcessDefinition<In, In, ProcessContext> PROCESS = ProcessDefinition.single("JOB_TARGET", 1,
        In.class, In.class, (in, ctx) -> in);

    @Test
    void aJobRunsAProcessOnACron() {
        JobDefinition<In> job = JobDefinition.cron("month-close", "0 5 0 1 * *", ZoneId.of("Asia/Tokyo"), PROCESS,
            In::new);

        assertThat(job.lockAtMostFor()).isEqualTo(JobDefinition.DEFAULT_LOCK_AT_MOST_FOR);
        assertThat(job.input().apply(Instant.EPOCH)).isEqualTo(new In(Instant.EPOCH));
        assertThat(job.lockAtMostFor(Duration.ofMinutes(1)).lockAtMostFor()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void declarationsAreChecked() {
        assertThatThrownBy(() -> JobDefinition.cron("has space", "* * * * * *", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> JobDefinition.cron("x".repeat(65), "* * * * * *", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("must match");
        assertThatThrownBy(() -> JobDefinition.cron("ok", " ", ZoneOffset.UTC, PROCESS, In::new))
            .hasMessageContaining("cron");
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", ZoneOffset.UTC, PROCESS, In::new)
            .lockAtMostFor(Duration.ZERO)).hasMessageContaining("positive");
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", null, PROCESS, In::new))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JobDefinition.cron("ok", "* * * * * *", ZoneOffset.UTC, null, In::new))
            .isInstanceOf(NullPointerException.class);
    }

    static final JobItems ITEMS = JobItems.template("it.job.items", at -> Map.of("now", at), "item_id");

    static JobDefinition<In> perItem() {
        return JobDefinition.forEach("expire", "0 * * * * *", ZoneOffset.UTC, ITEMS, PROCESS,
            (at, row) -> new In(at));
    }

    @Test
    void aSingleJobIsOfKindSingleAndHasNoItemSettings() {
        JobDefinition<In> job = JobDefinition.cron("tick", "0 * * * * *", ZoneOffset.UTC, PROCESS, In::new);
        assertThat(job.kind()).isEqualTo(JobDefinition.Kind.SINGLE);
        assertThat(job.items()).isNull();
        assertThat(job.itemInput()).isNull();
        assertThat(job.maxRunTime()).isNull();
        assertThat(job.toString()).contains("SINGLE", "tick", "JOB_TARGET");
        assertThatThrownBy(() -> job.maxItems(10)).hasMessageContaining("per-item");
        assertThatThrownBy(() -> job.parallelism(2)).hasMessageContaining("per-item");
        assertThatThrownBy(() -> job.itemLockWait(Duration.ofSeconds(1))).hasMessageContaining("per-item");
        assertThatThrownBy(() -> job.itemRetry(Duration.ofSeconds(1), Duration.ofSeconds(2)))
            .hasMessageContaining("per-item");
        assertThatThrownBy(() -> job.maxRunTime(Duration.ofSeconds(1))).hasMessageContaining("per-item");
    }

    @Test
    void aPerItemJobHasDefaultsAndChangedCopies() {
        JobDefinition<In> job = perItem();
        assertThat(job.kind()).isEqualTo(JobDefinition.Kind.PER_ITEM);
        assertThat(job.input()).isNull();
        assertThat(job.items()).isEqualTo(ITEMS);
        assertThat(job.items().parameters().apply(Instant.EPOCH)).containsEntry("now", Instant.EPOCH);
        assertThat(job.itemInput().apply(Instant.EPOCH, Map.of())).isEqualTo(new In(Instant.EPOCH));
        assertThat(job.maxItems()).isEqualTo(500);
        assertThat(job.parallelism()).isEqualTo(1);
        assertThat(job.itemLockWait()).isEqualTo(Duration.ofSeconds(2));
        assertThat(job.itemRetry()).isEqualTo(new JobDefinition.ItemRetry(Duration.ofMinutes(1), Duration.ofHours(1)));
        assertThat(job.maxRunTime()).isEqualTo(Duration.ofMinutes(5));

        JobDefinition<In> changed = job.maxItems(2000).parallelism(4).itemLockWait(Duration.ofMillis(500))
            .itemRetry(Duration.ofSeconds(10), Duration.ofMinutes(5)).maxRunTime(Duration.ofMinutes(5))
            .lockAtMostFor(Duration.ofMinutes(20));
        assertThat(changed.maxItems()).isEqualTo(2000);
        assertThat(changed.parallelism()).isEqualTo(4);
        assertThat(changed.itemLockWait()).isEqualTo(Duration.ofMillis(500));
        assertThat(changed.itemRetry().first()).isEqualTo(Duration.ofSeconds(10));
        assertThat(changed.maxRunTime()).isEqualTo(Duration.ofMinutes(5));
        assertThat(changed.lockAtMostFor()).isEqualTo(Duration.ofMinutes(20));
        assertThat(job.maxItems()).as("unchanged original").isEqualTo(500);
        // Without its own maxRunTime, a run stops starting items at half the lock.
        assertThat(job.lockAtMostFor(Duration.ofMinutes(30)).maxRunTime()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void perItemSettingsAreChecked() {
        JobDefinition<In> job = perItem();
        assertThatThrownBy(() -> job.maxItems(0)).hasMessageContaining("maxItems");
        assertThatThrownBy(() -> job.maxItems(10_001)).hasMessageContaining("maxItems");
        assertThat(job.maxItems(10_000).maxItems()).isEqualTo(10_000);
        assertThatThrownBy(() -> job.parallelism(0)).hasMessageContaining("parallelism");
        assertThatThrownBy(() -> job.parallelism(9)).hasMessageContaining("parallelism");
        assertThatThrownBy(() -> job.itemLockWait(Duration.ZERO)).hasMessageContaining("itemLockWait");
        assertThatThrownBy(() -> job.itemRetry(Duration.ZERO, Duration.ofHours(1))).hasMessageContaining("first");
        assertThatThrownBy(() -> job.itemRetry(Duration.ofHours(2), Duration.ofHours(1))).hasMessageContaining("longest");
        assertThatThrownBy(() -> job.maxRunTime(Duration.ZERO)).hasMessageContaining("positive");
        assertThatThrownBy(() -> job.maxRunTime(Duration.ofMinutes(10))).hasMessageContaining("shorter than");
        assertThatThrownBy(() -> job.maxRunTime(Duration.ofMinutes(5)).lockAtMostFor(Duration.ofMinutes(5)))
            .hasMessageContaining("shorter than");
        assertThatThrownBy(() -> JobDefinition.forEach("x", "* * * * * *", ZoneOffset.UTC, null, PROCESS,
            (at, row) -> new In(at))).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> JobDefinition.forEach("x", "* * * * * *", ZoneOffset.UTC, ITEMS, PROCESS, null))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theSelectionIsDeclared() {
        assertThat(JobItems.template("t", "k").parameters().apply(Instant.EPOCH)).isEmpty();
        assertThatThrownBy(() -> JobItems.template(" ", "k")).hasMessageContaining("templateId");
        assertThatThrownBy(() -> JobItems.template("t", "")).hasMessageContaining("keyColumn");
        assertThatThrownBy(() -> JobItems.template("t", null, "k")).isInstanceOf(NullPointerException.class);
        assertThat(JobItems.ITEM_KEY.matcher("0190d6c4-1f2a-7b3c-8d4e-5f6a7b8c9d0e").matches()).isTrue();
        assertThat(JobItems.ITEM_KEY.matcher("x".repeat(37)).matches()).isFalse();
        assertThat(JobItems.ITEM_KEY.matcher("a b").matches()).isFalse();
    }

    @Test
    void itemIdempotencyKeysNameTheJobTheTimeAndTheItemAndFitTheColumn() {
        Instant at = Instant.parse("2026-10-11T03:10:00Z");
        assertThat(JobDefinition.itemIdempotencyKey("commerce.unpaid-order-expire", at, "k1"))
            .isEqualTo("job:commerce.unpaid-order-expire:" + at.toEpochMilli() + ":k1");
        String longest = JobDefinition.itemIdempotencyKey("n".repeat(64), Instant.ofEpochMilli(9_999_999_999_999L),
            "k".repeat(36));
        assertThat(longest).hasSize(119);
        assertThat(longest.length()).isLessThanOrEqualTo(JobDefinition.MAX_IDEMPOTENCY_KEY);
    }

    @Test
    void failingItemsWaitLongerEachTimeUpToTheLongestDelay() {
        JobDefinition.ItemRetry retry = JobDefinition.DEFAULT_ITEM_RETRY;
        assertThat(retry.delayAfter(0)).isZero();
        assertThat(retry.delayAfter(1)).isZero();
        assertThat(retry.delayAfter(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(retry.delayAfter(3)).isEqualTo(Duration.ofMinutes(2));
        assertThat(retry.delayAfter(4)).isEqualTo(Duration.ofMinutes(4));
        assertThat(retry.delayAfter(7)).isEqualTo(Duration.ofMinutes(32));
        assertThat(retry.delayAfter(8)).as("64 minutes, capped").isEqualTo(Duration.ofHours(1));
        assertThat(retry.delayAfter(9)).isEqualTo(Duration.ofHours(1));
        assertThat(retry.delayAfter(70)).isEqualTo(Duration.ofHours(1));
        assertThat(retry.delayAfter(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
    }
}
