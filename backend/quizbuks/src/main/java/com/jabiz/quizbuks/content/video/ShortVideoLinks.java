package com.jabiz.quizbuks.content.video;

import com.jabiz.external.ExternalService;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Short links to one video (docs/quizbuks/plans/Q99-more-video-sites.md, Q99b): Bilibili shares its videos as
 * {@code https://b23.tv/<code>}, which only its own server turns into the video's address. The sponsor console asks
 * {@code QB_VIDEO_LINK_RESOLVE} for the full address before saving, and the material keeps that one, which the App
 * plays as every other video. Only b23.tv is asked (the service's only host); the answer must be a redirect to one
 * Bilibili video, written back as its plain address without the sharing parameters.
 */
public final class ShortVideoLinks {

    public static final String SERVICE = "video-short-links";

    /** b23.tv; tests point the service at a local stub ({@code jabiz.external-services.video-short-links.*}). */
    public static final ExternalService DEFINITION = ExternalService.define(SERVICE, s -> s
        .baseUrl("https://b23.tv").allowedHosts("b23.tv")
        .connectTimeout(Duration.ofSeconds(5)).requestTimeout(Duration.ofSeconds(8))
        .maxResponseSize(64 * 1024)
        .allowInDryRun());

    private static final Set<String> SHORT_HOSTS = Set.of("b23.tv", "www.b23.tv");
    private static final Pattern CODE = Pattern.compile("[0-9A-Za-z]{4,16}");
    private static final Set<String> BILIBILI_HOSTS = Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com");
    private static final Pattern BILIBILI_VIDEO = Pattern.compile("/video/(BV[0-9A-Za-z]{10})/?");

    private ShortVideoLinks() {}

    /** The short link's code when the address is one ({@code https://b23.tv/<code>}); empty for any other address. */
    public static Optional<String> code(String url) {
        URI uri = uri(url);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null || !SHORT_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))
            || uri.getUserInfo() != null || uri.getPort() != -1) {
            return Optional.empty();
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        String code = path.startsWith("/") ? path.substring(1) : path;
        if (code.endsWith("/")) {
            code = code.substring(0, code.length() - 1);
        }
        return CODE.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    /**
     * The plain address of the Bilibili video a redirect leads to ({@code https://www.bilibili.com/video/<BV id>}),
     * its sharing parameters left out; empty when the redirect leads anywhere else.
     */
    public static Optional<String> videoOf(String location) {
        URI uri = uri(location);
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
            || !BILIBILI_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null
            || uri.getPort() != -1 || uri.getRawPath() == null) {
            return Optional.empty();
        }
        Matcher video = BILIBILI_VIDEO.matcher(uri.getRawPath());
        return video.matches() ? Optional.of("https://www.bilibili.com/video/" + video.group(1)) : Optional.empty();
    }

    private static URI uri(String value) {
        if (value == null || value.isBlank() || value.length() > 2_000) {
            return null;
        }
        try {
            return new URI(value.strip());
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
