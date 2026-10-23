package com.jabiz.quizbuks.content;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The video links QuizBuks shows in its own pages (docs/quizbuks/plans/Q19-embedded-video.md): a YouTube or Vimeo
 * video, as the address of its player. YouTube's player is its no-cookie domain; nothing plays by itself. The App's
 * content security policy frames these two players only (`frame-src`), so any other link stays a link. The App's
 * {@code qb/video.ts} reads links the same way (its test has the cases of {@code VideoEmbedsTest}).
 */
public final class VideoEmbeds {

    /** The players the App frames: its {@code frame-src}. */
    public static final String YOUTUBE_PLAYER = "https://www.youtube-nocookie.com/embed/";
    public static final String VIMEO_PLAYER = "https://player.vimeo.com/video/";

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIMEO_ID = Pattern.compile("[0-9]{1,12}");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> VIMEO_HOSTS = Set.of("vimeo.com", "www.vimeo.com", "player.vimeo.com");

    private VideoEmbeds() {
    }

    /** The player's address for a YouTube or Vimeo video link; empty for any other address. */
    public static Optional<String> player(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http") || uri.getUserInfo() != null || uri.getPort() != -1) {
            return Optional.empty();
        }
        // As the App reads it: the raw path, empty segments kept (a trailing slash is not the same video).
        String[] path = (uri.getRawPath() == null ? "" : uri.getRawPath()).split("/", -1);
        if (host.equals("youtu.be")) {
            return youtube(path.length > 1 ? path[1] : null);
        }
        if (YOUTUBE_HOSTS.contains(host)) {
            if (path.length == 2 && path[1].equals("watch")) {
                return youtube(query(uri.getRawQuery(), "v"));
            }
            if (path.length == 3 && (path[1].equals("shorts") || path[1].equals("embed") || path[1].equals("live"))) {
                return youtube(path[2]);
            }
            return Optional.empty();
        }
        if (VIMEO_HOSTS.contains(host)) {
            // vimeo.com/<id>, player.vimeo.com/video/<id>; channels and showcases are not one video.
            String id = host.equals("player.vimeo.com")
                ? (path.length == 3 && path[1].equals("video") ? path[2] : null)
                : (path.length == 2 ? path[1] : null);
            return id != null && VIMEO_ID.matcher(id).matches() ? Optional.of(VIMEO_PLAYER + id) : Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> youtube(String id) {
        return id != null && YOUTUBE_ID.matcher(id).matches() ? Optional.of(YOUTUBE_PLAYER + id) : Optional.empty();
    }

    /** One parameter of a raw query; null when absent. Video ids have no characters that need decoding. */
    private static String query(String raw, String name) {
        if (raw == null) {
            return null;
        }
        for (String pair : raw.split("&")) {
            int at = pair.indexOf('=');
            if (at > 0 && pair.substring(0, at).equals(name)) {
                return pair.substring(at + 1);
            }
        }
        return null;
    }
}
