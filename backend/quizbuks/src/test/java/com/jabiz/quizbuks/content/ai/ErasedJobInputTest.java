package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * F-M15c: the platform's purge of an erased account overwrites the input of its AI jobs with {@code {"erased": true}}
 * (ErasurePurger). A job that runs after the purge (one queued while {@code qb.ai.enabled} was off, say) never calls
 * the provider: an input the platform cannot read (a number missing) is its permanent {@code BAD_INPUT} before the
 * handler; one it reads leniently (unknown properties ignored) is all nulls, and the handler fails for good.
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
        // Its count is a number: the platform cannot read the purged input (BAD_INPUT, permanent, no handler); were it
        // read, the handler would refuse it.
        assertThatThrownBy(() -> JSON.readValue(PURGED, AiQuestionsJob.Input.class))
            .isInstanceOf(tools.jackson.core.JacksonException.class);
        assertErased(() -> handler.run(new AiQuestionsJob.Input(null, null, null, 0, null, null, null, null, null,
            null, null), null));
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
    void aSiteProfileJobWithAPurgedInputCannotBeReadSoNoHandlerRuns() {
        // Its switches are booleans: the platform cannot read the purged input (BAD_INPUT, permanent, no handler).
        assertThatThrownBy(() -> JSON.readValue(PURGED, SiteProfileJob.Input.class))
            .isInstanceOf(tools.jackson.core.JacksonException.class);
    }

    private static void assertErased(Runnable run) {
        ExternalJobException failure = catchThrowableOfType(ExternalJobException.class, run::run);
        assertThat(failure).as("an erased input fails").isNotNull();
        assertThat(failure.code()).isEqualTo(AiCodes.INPUT_ERASED);
        assertThat(failure.isPermanent()).isTrue();
    }
}
