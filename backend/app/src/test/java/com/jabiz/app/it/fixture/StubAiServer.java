package com.jabiz.app.it.fixture;

import com.jabiz.runtime.test.StubHttpServer;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the demo AI service {@code demo-ai} (decision D38): the platform's {@link StubHttpServer} whose
 * {@code POST /v1/moderate} flags texts containing {@value #FLAGGED_WORD}. Any path can be scripted instead.
 */
public final class StubAiServer implements AutoCloseable {

    /** The word {@code /v1/moderate} flags. */
    public static final String FLAGGED_WORD = "badword";

    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final StubHttpServer server;

    private StubAiServer() {
        this.server = StubHttpServer.start(StubAiServer::answer);
    }

    public static StubAiServer start() {
        return new StubAiServer();
    }

    /** {@code http://127.0.0.1:<port>/v1}: the base address to configure for {@code demo-ai}. */
    public String baseUrl() {
        return server.url("/v1");
    }

    public int port() {
        return server.port();
    }

    public List<StubHttpServer.Received> requests() {
        return server.requests();
    }

    public List<StubHttpServer.Received> requests(String path) {
        return server.requests(path);
    }

    /** The next request to {@code path} gets this answer instead of the default one. */
    public void script(String path, int status, String body, Duration delay) {
        server.script(path, StubHttpServer.Answer.json(status, body).after(delay));
    }

    public void reset() {
        server.reset();
    }

    private static StubHttpServer.Answer answer(StubHttpServer.Received request) {
        if (request.path().equals("/v1/moderate") && request.method().equals("POST")) {
            Matcher text = TEXT.matcher(request.body());
            boolean flagged = text.find() && text.group(1).toLowerCase(Locale.ROOT).contains(FLAGGED_WORD);
            return StubHttpServer.Answer.json(200, flagged ? "{\"flagged\":true,\"categories\":[\"harassment\"]}"
                : "{\"flagged\":false,\"categories\":[]}");
        }
        if (request.path().equals("/v1/moderate")) {
            return StubHttpServer.Answer.json(200, "{\"flagged\":false,\"categories\":[]}");
        }
        return StubHttpServer.Answer.json(404, "{\"error\":\"not found\"}");
    }

    @Override
    public void close() {
        server.close();
    }
}
