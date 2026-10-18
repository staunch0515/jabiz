package com.jabiz.app.it.fixture;

import com.jabiz.runtime.test.FileSamples;
import com.jabiz.runtime.test.StubHttpServer;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A local stand-in for the demo AI service {@code demo-ai} (decision D38): the platform's {@link StubHttpServer} whose
 * {@code POST /v1/moderate} flags texts containing {@value #FLAGGED_WORD}, whose {@code POST /v1/generate} writes
 * {@value #GENERATED_PREFIX} and the product's name, reporting fixed token usage, and whose {@code POST /v1/images}
 * answers {@link #GENERATED_IMAGE} (decision D50). Any path can be scripted instead.
 */
public final class StubAiServer implements AutoCloseable {

    /** The word {@code /v1/moderate} flags. */
    public static final String FLAGGED_WORD = "badword";

    /** How {@code /v1/generate} starts its text, followed by the product's name. */
    public static final String GENERATED_PREFIX = "Discover ";
    public static final long PROMPT_TOKENS = 12;
    public static final long COMPLETION_TOKENS = 34;

    /**
     * What {@code /v1/images} answers by default: a photo whose EXIF holds a GPS position
     * ({@link FileSamples#SECRET_MARKER}), which the platform must strip when it stores the job's file.
     */
    public static final byte[] GENERATED_IMAGE = FileSamples.jpegWithExif(800, 600, 1);

    private static final Pattern TEXT = Pattern.compile("\"text\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern IMAGE = Pattern.compile("\"image\"\\s*:\\s*\"([A-Za-z0-9+/=]*)\"");

    /**
     * An answer of {@code /v1/images} with {@code image} and usage {@code receivedBytes}: how many bytes of image the
     * request carried.
     */
    public static String imageAnswer(byte[] image, long receivedBytes) {
        return "{\"image\":\"" + Base64.getEncoder().encodeToString(image) + "\",\"usage\":{\"receivedBytes\":"
            + receivedBytes + "}}";
    }
    private static final Pattern PRODUCT_NAME = Pattern.compile("\"productName\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

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

    /** The most requests answered at the same time since the stub started or was reset. */
    public int maxConcurrent() {
        return server.maxConcurrent();
    }

    /** The next request to {@code path} gets this answer, with the given headers. */
    public void script(StubHttpServer.Answer answer, String path) {
        server.script(path, answer);
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
        if (request.path().equals("/v1/images") && request.method().equals("POST")) {
            Matcher image = IMAGE.matcher(request.body());
            long received = image.find() ? Base64.getDecoder().decode(image.group(1)).length : 0;
            return StubHttpServer.Answer.json(200, imageAnswer(GENERATED_IMAGE, received));
        }
        if (request.path().equals("/v1/generate") && request.method().equals("POST")) {
            Matcher name = PRODUCT_NAME.matcher(request.body());
            return StubHttpServer.Answer.json(200, "{\"text\":\"" + GENERATED_PREFIX
                + (name.find() ? name.group(1) : "the product") + ".\",\"usage\":{\"promptTokens\":"
                + PROMPT_TOKENS + ",\"completionTokens\":" + COMPLETION_TOKENS + "}}");
        }
        return StubHttpServer.Answer.json(404, "{\"error\":\"not found\"}");
    }

    @Override
    public void close() {
        server.close();
    }
}
