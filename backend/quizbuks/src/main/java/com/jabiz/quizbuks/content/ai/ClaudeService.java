package com.jabiz.quizbuks.content.ai;

import com.jabiz.external.ExternalService;

import java.time.Duration;
import java.util.List;

/**
 * Claude (Anthropic's Messages API) as QuizBuks reaches it (docs/quizbuks/plans/Q8-ai.md, D-Q8-1, A-1): the external
 * service {@value #SERVICE}, through the platform's JDK HTTP client only (platform decision D38: an SDK would open
 * connections the host list does not govern), called by the asynchronous external job {@code qb.ai.questions} only.
 *
 * <p>The API key comes from {@value #API_KEY} only and is sent in the {@code x-api-key} header, whose value the
 * platform never prints. Development, the tests and the end-to-end tests point the service at a stub on this machine
 * ({@code jabiz.external-services.anthropic.base-url | allowed-hosts}; tools/quizbuks/claude-stub), which never calls
 * the network. The request timeout is the platform's longest (two minutes): a non-streaming answer of
 * {@link ClaudeApi#MAX_TOKENS} tokens at effort {@code medium} fits it, and a longer one fails the attempt, which is
 * tried again (the job's own timeout bounds all of it).
 */
public final class ClaudeService {

    public static final String SERVICE = "anthropic";
    /** The environment variable of the API key. */
    public static final String API_KEY = "ANTHROPIC_API_KEY";
    /** The API version every request names. */
    public static final String API_VERSION = "2023-06-01";

    /** The only models {@code qb.ai.model} may name (A-2): checked by the parameter's kind and again per request. */
    public static final String OPUS = "claude-opus-5-5";
    public static final String SONNET = "claude-sonnet-5-5";
    public static final String HAIKU = "claude-haiku-5-5";
    public static final List<String> MODELS = List.of(OPUS, SONNET, HAIKU);

    /** The efforts {@code qb.ai.effort} may name: thinking cannot be switched off on Opus 5.5, only made shallower. */
    public static final List<String> EFFORTS = List.of("low", "medium", "high");

    public static final ExternalService DEFINITION = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://api.anthropic.com").allowedHosts("api.anthropic.com")
        .secret("apiKey", API_KEY)
        .connectTimeout(Duration.ofSeconds(10)).requestTimeout(ExternalService.MAX_REQUEST_TIMEOUT)
        .maxResponseSize(1024 * 1024));

    private ClaudeService() {}
}
