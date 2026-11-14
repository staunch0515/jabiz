package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * F-M15c: the platform's purge of an erased account overwrites the input of its AI jobs with {@code {"erased": true}}
 * (ErasurePurger), and the platform reads it leniently (unknown properties are ignored), so a job that runs after the
 * purge (one queued while {@code qb.ai.enabled} was off, say) gets an input of nothing but nulls. It fails at once, for
 * good, without calling the provider.
 */
class ErasedJobInputTest {

    private static final String PURGED = "{\"erased\": true}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void aQuestionsJobWithAPurgedInputFailsWithoutCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        AiQuestionsHandler handler = new AiQuestionsHandler((input, ctx) -> {
            calls.incrementAndGet();
            return null;
        });
        assertErased(() -> handler.run(JSON.readValue(PURGED, AiQuestionsJob.Input.class), null));
        assertThat(calls).hasValue(0);
    }

    @Test
    void aTranslationJobWithAPurgedInputFailsWithoutCallingTheProvider() {
        AtomicInteger calls = new AtomicInteger();
        AiTranslateHandler handler = new AiTranslateHandler((input, ctx) -> {
            calls.incrementAndGet();
            return null;
        });
        assertErased(() -> handler.run(JSON.readValue(PURGED, AiTranslateJob.Input.class), null));
        assertThat(calls).hasValue(0);
    }

    @Test
    void aSiteProfileJobWithAPurgedInputFailsBeforeItReadsAnything() {
        // Its context is never touched: a call to the provider would need it.
        SiteProfileHandler handler = new SiteProfileHandler(JSON, Duration.ofMinutes(1));
        assertErased(() -> handler.run(JSON.readValue(PURGED, SiteProfileJob.Input.class), null));
    }

    private static void assertErased(Runnable run) {
        ExternalJobException failure = catchThrowableOfType(ExternalJobException.class, run::run);
        assertThat(failure).as("an erased input fails").isNotNull();
        assertThat(failure.code()).isEqualTo(AiCodes.INPUT_ERASED);
        assertThat(failure.isPermanent()).isTrue();
    }
}
