package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalJobContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Comparisons from Claude ({@link ClaudeApi}, {@link AiCheckPrompt}): one call a batch, as
 * {@link ClaudeContentTranslator}; a pair it leaves out or answers twice is counted as invalid.
 */
@Component
class ClaudeTranslationChecker implements TranslationChecker {

    private final JsonMapper json;
    private final Duration requestTimeout;

    ClaudeTranslationChecker(JsonMapper json, ClaudeCalls.Settings settings) {
        this.json = json;
        this.requestTimeout = settings.requestTimeout();
    }

    @Override
    public AiCheckJob.Result check(AiCheckJob.Input input, ExternalJobContext ctx) {
        List<AiCheckJob.Finding> findings = new ArrayList<>();
        String servedModel = input.model();
        boolean fellBack = false;
        int invalid = 0;
        for (List<AiCheckJob.Pair> batch : AiCheckJob.batches(input.pairs())) {
            ClaudeApi.Request request = new ClaudeApi.Request(input.model(), AiCheckJob.EFFORT,
                AiCheckJob.BATCH_MAX_TOKENS, AiCheckPrompt.SYSTEM, AiCheckPrompt.user(input, batch, json),
                input.userKey(), AiCheckPrompt.schema(), List.of());
            ClaudeCalls.Reserved reserved = new ClaudeCalls.Reserved(AiCheckJob.TYPE, input.reservedInputTokens(),
                input.reservedInputTokens() == null ? Long.MAX_VALUE
                    : input.reservedInputTokens() + AiCheckJob.BATCH_MAX_TOKENS);
            ClaudeApi.Answer answer = ClaudeCalls.ask(ctx, json, request, requestTimeout, reserved);
            List<AiCheckJob.Finding> answered = AiCheckPrompt.findings(answer.text(), json);
            if (answered == null) {
                throw ClaudeErrors.badResponse("is not the verdicts' document");
            }
            servedModel = answer.servedModel();
            fellBack |= answer.fellBack();
            Map<Integer, AiCheckJob.Pair> asked = new HashMap<>();
            batch.forEach(pair -> asked.put(pair.no(), pair));
            Set<Integer> seen = new HashSet<>();
            for (AiCheckJob.Finding finding : answered) {
                AiCheckJob.Pair pair = asked.get(finding.no());
                if (pair != null && seen.add(finding.no())) {
                    findings.add(new AiCheckJob.Finding(pair.no(), pair.key(), finding.consistent(),
                        finding.note()));
                }
            }
            invalid += asked.size() - seen.size();
        }
        return new AiCheckJob.Result(servedModel, fellBack, findings, invalid);
    }
}
