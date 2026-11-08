package com.jabiz.quizbuks.content;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The video links QuizBuks shows in its own pages (docs/quizbuks/plans/Q19-embedded-video.md,
 * Q99-more-video-sites.md): one video of YouTube, Vimeo, Bilibili, Niconico, Dailymotion, Youku or Tencent Video, as
 * the address of its player. YouTube's player is its no-cookie domain. The App's content security policy frames these
 * players only (`frame-src`), so any other link stays a link. Only YouTube's and Vimeo's players say where they are
 * ({@link #trackable}): only their videos can be the one to watch before answering (Q75, D-Q99-2). The App's
 * {@code qb/video.ts} reads links the same way (its test has the cases of {@code VideoEmbedsTest}).
 */
public final class VideoEmbeds {

    /** The players the App frames: its {@code frame-src}. */
    public static final String YOUTUBE_PLAYER = "https://www.youtube-nocookie.com/embed/";
    public static final String VIMEO_PLAYER = "https://player.vimeo.com/video/";
    public static final String BILIBILI_PLAYER = "https://player.bilibili.com/player.html?bvid=";
    /** Bilibili's player starts by itself unless told not to. */
    public static final String BILIBILI_SUFFIX = "&autoplay=0";
    public static final String NICONICO_PLAYER = "https://embed.nicovideo.jp/watch/";
    public static final String DAILYMOTION_PLAYER = "https://www.dailymotion.com/embed/video/";
    public static final String YOUKU_PLAYER = "https://player.youku.com/embed/";
    public static final String TENCENT_PLAYER = "https://v.qq.com/txp/iframe/player.html?vid=";

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern VIMEO_ID = Pattern.compile("[0-9]{1,12}");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com",
        "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> VIMEO_HOSTS = Set.of("vimeo.com", "www.vimeo.com", "player.vimeo.com");
    private static final Pattern BILIBILI_ID = Pattern.compile("BV[0-9A-Za-z]{10}");
    private static final Set<String> BILIBILI_HOSTS = Set.of("bilibili.com", "www.bilibili.com", "m.bilibili.com");
    private static final Pattern NICONICO_ID = Pattern.compile("(sm|nm|so)[0-9]{1,12}");
    private static final Set<String> NICONICO_HOSTS = Set.of("nicovideo.jp", "www.nicovideo.jp", "sp.nicovideo.jp",
        "embed.nicovideo.jp");
    private static final Pattern DAILYMOTION_ID = Pattern.compile("x[0-9a-z]{4,12}");
    private static final Set<String> DAILYMOTION_HOSTS = Set.of("dailymotion.com", "www.dailymotion.com");
    private static final Pattern YOUKU_ID = Pattern.compile("[A-Za-z0-9]{8,28}={0,2}");
    private static final Pattern TENCENT_ID = Pattern.compile("[a-z0-9]{11}");
    private static final Pattern TENCENT_COVER = Pattern.compile("[a-z0-9]{15}");

    private VideoEmbeds() {
    }

    /**
     * The video sites QuizBuks recognises (F-S11): each is named by its key in the controlled parameter
     * {@code qb.video.mainland-accessible-sites}. A link whose site this does not recognise is none of them.
     */
    public enum Site {
        YOUTUBE("youtube", YOUTUBE_PLAYER), VIMEO("vimeo", VIMEO_PLAYER), BILIBILI("bilibili", BILIBILI_PLAYER),
        NICONICO("niconico", NICONICO_PLAYER), DAILYMOTION("dailymotion", DAILYMOTION_PLAYER),
        YOUKU("youku", YOUKU_PLAYER), TENCENT("tencent", TENCENT_PLAYER);

        private final String key;
        private final String player;

        Site(String key, String player) {
            this.key = key;
            this.player = player;
        }

        /** The site's key, as the parameter lists it (lower case). */
        public String key() {
            return key;
        }

        /** The site of a key (case and surrounding blanks aside); empty for any other text. */
        public static Optional<Site> ofKey(String key) {
            String wanted = key == null ? "" : key.strip().toLowerCase(Locale.ROOT);
            for (Site site : values()) {
                if (site.key.equals(wanted)) {
                    return Optional.of(site);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * The site of a link to one video, as {@link #player} reads it (F-S11); empty for any other address, which a
     * video link may well be (a material's link is any address).
     */
    public static Optional<Site> site(String url) {
        return player(url).flatMap(player -> java.util.Arrays.stream(Site.values())
            .filter(site -> player.startsWith(site.player)).findFirst());
    }

    /**
     * Whether a player (as {@link #player} gives it) says where it is, so that the App can tell that it was watched:
     * YouTube's and Vimeo's (Q75). The others play, but cannot be the video to watch before answering.
     */
    public static boolean trackable(String player) {
        return player != null && (player.startsWith(YOUTUBE_PLAYER) || player.startsWith(VIMEO_PLAYER));
    }

    /** The player of a video link whose player says where it is ({@link #trackable}); empty for any other. */
    public static Optional<String> trackablePlayer(String url) {
        return player(url).filter(VideoEmbeds::trackable);
    }

    /** The player's address for a link to one video of the sites above; empty for any other address. */
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
        if (BILIBILI_HOSTS.contains(host)) {
            // bilibili.com/video/<BV id>, its trailing slash too (as Bilibili shares it).
            return (path.length == 3 || path.length == 4 && path[3].isEmpty()) && path[1].equals("video")
                ? match(BILIBILI_ID, path[2], BILIBILI_PLAYER, BILIBILI_SUFFIX) : Optional.empty();
        }
        if (host.equals("player.bilibili.com")) {
            return path.length == 2 && path[1].equals("player.html")
                ? match(BILIBILI_ID, query(uri.getRawQuery(), "bvid"), BILIBILI_PLAYER, BILIBILI_SUFFIX)
                : Optional.empty();
        }
        if (NICONICO_HOSTS.contains(host)) {
            return path.length == 3 && path[1].equals("watch")
                ? match(NICONICO_ID, path[2], NICONICO_PLAYER, "") : Optional.empty();
        }
        if (host.equals("nico.ms")) {
            return path.length == 2 ? match(NICONICO_ID, path[1], NICONICO_PLAYER, "") : Optional.empty();
        }
        if (DAILYMOTION_HOSTS.contains(host)) {
            String id = path.length == 3 && path[1].equals("video") ? path[2]
                : path.length == 4 && path[1].equals("embed") && path[2].equals("video") ? path[3] : null;
            return match(DAILYMOTION_ID, id, DAILYMOTION_PLAYER, "");
        }
        if (host.equals("dai.ly")) {
            return path.length == 2 ? match(DAILYMOTION_ID, path[1], DAILYMOTION_PLAYER, "") : Optional.empty();
        }
        if (host.equals("v.youku.com")) {
            // v.youku.com/v_show/id_<id>.html
            if (path.length == 3 && path[1].equals("v_show") && path[2].startsWith("id_") && path[2].endsWith(".html")) {
                return match(YOUKU_ID, path[2].substring(3, path[2].length() - 5), YOUKU_PLAYER, "");
            }
            return Optional.empty();
        }
        if (host.equals("player.youku.com")) {
            return path.length == 3 && path[1].equals("embed") ? match(YOUKU_ID, path[2], YOUKU_PLAYER, "")
                : Optional.empty();
        }
        if (host.equals("v.qq.com")) {
            // v.qq.com/x/page/<vid>.html, v.qq.com/x/cover/<cover>/<vid>.html, and the player itself.
            if (path.length == 4 && path[1].equals("x") && path[2].equals("page") && path[3].endsWith(".html")) {
                return match(TENCENT_ID, path[3].substring(0, path[3].length() - 5), TENCENT_PLAYER, "");
            }
            if (path.length == 5 && path[1].equals("x") && path[2].equals("cover")
                && TENCENT_COVER.matcher(path[3]).matches() && path[4].endsWith(".html")) {
                return match(TENCENT_ID, path[4].substring(0, path[4].length() - 5), TENCENT_PLAYER, "");
            }
            if (path.length == 4 && path[1].equals("txp") && path[2].equals("iframe") && path[3].equals("player.html")) {
                return match(TENCENT_ID, query(uri.getRawQuery(), "vid"), TENCENT_PLAYER, "");
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> match(Pattern id, String value, String player, String suffix) {
        return value != null && id.matcher(value).matches() ? Optional.of(player + value + suffix) : Optional.empty();
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
