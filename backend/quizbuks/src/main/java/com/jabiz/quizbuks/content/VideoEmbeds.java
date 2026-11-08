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
 *
 * <p>F-S15 (docs/quizbuks/plans/impl/F-S15.md): a video link may be on any https address. Besides the seven sites
 * ({@link #sitePlayer}), a video file ({@code .mp4}, {@code .webm}, {@code .mov} on any host, {@link #filePlayer}) plays
 * in the pages' own {@code <video>}, which says where it is ({@link #trackable}); any other https address
 * ({@link #pagePlayer}) is a player page framed in a sandbox. Links are read as text, not through a URI parser, the
 * same way as the App (a browser's parser resolves dot segments).
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
    /**
     * F-S15: an https address as written: a host name, a path of plain segments (unreserved characters, sub-delimiters,
     * {@code :} and {@code @}, and escapes), an optional query and fragment of the same characters and {@code /} and
     * {@code ?}; no user, no port.
     */
    private static final String CHARS = "(?:[A-Za-z0-9._~!$&'()*+,;=:@-]|%[0-9A-Fa-f]{2})";
    private static final Pattern ADDRESS = Pattern.compile("(?i:https)://([A-Za-z0-9.-]+)((?:/" + CHARS + "+)*/?)"
        + "(\\?(?:" + CHARS + "|[/?])*)?(#(?:" + CHARS + "|[/?])*)?");
    /** A plain host name: labels of letters, digits and inner hyphens, at least two, the last not a number. */
    private static final Pattern HOST = Pattern.compile(
        "(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");
    /** F-S15: the endings of a video file's path the pages play with {@code <video>}. */
    public static final java.util.List<String> FILE_EXTENSIONS = java.util.List.of(".mp4", ".webm", ".mov");
    /** The hosts of the seven sites: an address there that is not one video is no video (a channel, a short link). */
    private static final Set<String> SITE_HOSTS = siteHosts();

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
        return sitePlayer(url).flatMap(player -> java.util.Arrays.stream(Site.values())
            .filter(site -> player.startsWith(site.player)).findFirst());
    }

    /**
     * Whether a player (as {@link #player} gives it) says where it is, so that the App can tell that it was watched:
     * YouTube's and Vimeo's (Q75), and (F-S15) a video file's {@code <video>}. The others play, but cannot be the video
     * to watch before answering.
     */
    public static boolean trackable(String player) {
        return player != null && (player.startsWith(YOUTUBE_PLAYER) || player.startsWith(VIMEO_PLAYER)
            || kind(player).filter(Kind.FILE::equals).isPresent());
    }

    /**
     * Whether a video link is a YouTube Shorts link ({@code youtube.com/shorts/<id>}, F-S10): such a video is vertical
     * whatever the material says. Read as {@link #player} reads links; the App's {@code qb/video.ts} ({@code isShorts})
     * has the same cases.
     */
    public static boolean shorts(String url) {
        if (sitePlayer(url).isEmpty()) {
            return false;
        }
        URI uri = URI.create(url.trim());
        String[] path = (uri.getRawPath() == null ? "" : uri.getRawPath()).split("/", -1);
        return YOUTUBE_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) && path.length == 3
            && path[1].equals("shorts");
    }

    /** The player of a video link whose player says where it is ({@link #trackable}); empty for any other. */
    public static Optional<String> trackablePlayer(String url) {
        return player(url).filter(VideoEmbeds::trackable);
    }

    /**
     * The player's address for a link to any video QuizBuks plays (F-S15): one of the seven sites' ({@link #sitePlayer}),
     * else a video file ({@link #filePlayer}), else another https page ({@link #pagePlayer}); empty for anything else.
     */
    public static Optional<String> player(String url) {
        return sitePlayer(url).or(() -> filePlayer(url)).or(() -> pagePlayer(url));
    }

    /** The three kinds of player (F-S15). */
    public enum Kind { SITE, FILE, PAGE }

    /** The kind of a player as {@link #player} gives it; empty for any other address. */
    public static Optional<Kind> kind(String player) {
        if (player == null) {
            return Optional.empty();
        }
        if (sitePlayer(player).filter(player::equals).isPresent()) {
            return Optional.of(Kind.SITE);
        }
        if (filePlayer(player).filter(player::equals).isPresent()) {
            return Optional.of(Kind.FILE);
        }
        return pagePlayer(player).filter(player::equals).map(page -> Kind.PAGE);
    }

    /**
     * F-S15: a video file on any host but the seven sites': an https address (as {@link #pagePlayer} takes it) whose
     * path ends with one of
     * {@link #FILE_EXTENSIONS}; the query and fragment do not count (a signed {@code .mp4?sig=…} is one). The address
     * played is the link with its scheme and host in lower case.
     */
    public static Optional<String> filePlayer(String url) {
        Optional<java.util.regex.Matcher> address = address(url);
        if (address.isEmpty() || SITE_HOSTS.contains(address.get().group(1).toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        String path = address.get().group(2);
        String last = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean file = FILE_EXTENSIONS.stream().anyMatch(ending -> last.length() > ending.length() && last.endsWith(ending));
        return file ? Optional.of(normal(address.get())) : Optional.empty();
    }

    /**
     * F-S15: any other https page, framed as a player page in a sandbox: not one of the seven sites' hosts (an address
     * there that is not one video stays no video), and not a video file. The address played is the link with its scheme
     * and host in lower case.
     */
    public static Optional<String> pagePlayer(String url) {
        Optional<java.util.regex.Matcher> address = address(url);
        if (address.isEmpty() || SITE_HOSTS.contains(address.get().group(1).toLowerCase(Locale.ROOT))
            || filePlayer(url).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(normal(address.get()));
    }

    /** F-S15: the host of a video file's or a page's link (lower case), for F-S11's hosts; empty for any other. */
    public static Optional<String> host(String url) {
        return address(url).map(matcher -> matcher.group(1).toLowerCase(Locale.ROOT));
    }

    private static Optional<java.util.regex.Matcher> address(String url) {
        if (url == null) {
            return Optional.empty();
        }
        java.util.regex.Matcher matcher = ADDRESS.matcher(url.strip());
        if (!matcher.matches() || !HOST.matcher(matcher.group(1).toLowerCase(Locale.ROOT)).matches()) {
            return Optional.empty();
        }
        // Dot segments: a browser resolves them, so the App would read another address; none is taken.
        for (String segment : matcher.group(2).split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                return Optional.empty();
            }
        }
        return Optional.of(matcher);
    }

    private static String normal(java.util.regex.Matcher address) {
        return "https://" + address.group(1).toLowerCase(Locale.ROOT) + address.group(2)
            + (address.group(3) == null ? "" : address.group(3)) + (address.group(4) == null ? "" : address.group(4));
    }

    private static Set<String> siteHosts() {
        Set<String> hosts = new java.util.HashSet<>();
        hosts.addAll(YOUTUBE_HOSTS);
        hosts.addAll(VIMEO_HOSTS);
        hosts.addAll(BILIBILI_HOSTS);
        hosts.addAll(NICONICO_HOSTS);
        hosts.addAll(DAILYMOTION_HOSTS);
        hosts.addAll(Set.of("youtu.be", "player.bilibili.com", "b23.tv", "www.b23.tv", "nico.ms", "dai.ly",
            "geo.dailymotion.com", "v.youku.com", "player.youku.com", "youku.com", "www.youku.com", "v.qq.com"));
        return Set.copyOf(hosts);
    }

    /** The player's address for a link to one video of the seven sites above; empty for any other address. */
    public static Optional<String> sitePlayer(String url) {
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
