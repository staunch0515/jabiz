package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Translations from Claude ({@link ClaudeApi}, {@link AiTranslatePrompt}): one call a batch
 * ({@link AiTranslateJob#batches}), each with its usage and its failures as for questions ({@link ClaudeCalls}). An
 * answer that is not the translations' document is tried again (the whole attempt); a segment it leaves out or
 * answers twice is counted as invalid, not guessed.
 */
@Component
class ClaudeContentTranslator implements ContentTranslator {

    private final JsonMapper json;
    private final Duration requestTimeout;

    ClaudeContentTranslator(JsonMapper json, ClaudeCalls.Settings settings) {
        this.json = json;
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public AiTranslateJob.Result translate(AiTranslateJob.Input input, ExternalJobContext ctx) {
        List<AiTranslateJob.Translated> translations = new ArrayList<>();
        String servedModel = input.model();
        boolean fellBack = false;
        int invalid = 0;
        for (List<AiTranslateJob.Segment> batch : AiTranslateJob.batches(input.segments())) {
            ClaudeApi.Request request = new ClaudeApi.Request(input.model(), AiTranslateJob.EFFORT,
                AiTranslateJob.BATCH_MAX_TOKENS, AiTranslatePrompt.SYSTEM, AiTranslatePrompt.user(input, batch, json),
                input.userKey(), AiTranslatePrompt.schema(), List.of());
            ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(AiTranslateJob.TYPE, input.reservedInputTokens(),
                input.reservedInputTokens() == null ? Long.MAX_VALUE
                    : input.reservedInputTokens() + AiTranslateJob.BATCH_MAX_TOKENS);
            ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
            List<AiTranslateJob.Translated> answered = AiTranslatePrompt.translations(answer.text(), json);
            if (answered == null) {
                throw ClaudeErrors.badResponse("is not the translations' document");
            }
            servedModel = answer.servedModel();
            fellBack |= answer.fellBack();
            java.util.Map<Integer, AiTranslateJob.Segment> asked = new java.util.HashMap<>();
            batch.forEach(segment -> asked.put(segment.no(), segment));
            Set<Integer> seen = new HashSet<>();
            for (AiTranslateJob.Translated translated : answered) {
                AiTranslateJob.Segment segment = asked.get(translated.no());
                if (segment != null && seen.add(translated.no())) {
                    translations.add(new AiTranslateJob.Translated(segment.no(), segment.key(), segment.sourceHash(),
                        translated.text()));
                }
            }
            invalid += asked.size() - seen.size();
        }
        return new AiTranslateJob.Result(servedModel, fellBack, translations, invalid);
    }
}
