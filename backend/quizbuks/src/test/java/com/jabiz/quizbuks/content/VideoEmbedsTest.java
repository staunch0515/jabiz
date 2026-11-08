package com.jabiz.quizbuks.content;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/** Which links the App shows as a player, and the player's address (Q19); the App's {@code video.test.ts} has these. */
class VideoEmbedsTest {

    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=42, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://m.youtube.com/watch?v=dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "http://youtu.be/dQw4w9WgXcQ?si=abc, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/embed/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://www.youtube.com/live/dQw4w9WgXcQ, https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://vimeo.com/76979871, https://player.vimeo.com/video/76979871",
        "https://player.vimeo.com/video/76979871, https://player.vimeo.com/video/76979871",
        "'  https://vimeo.com/76979871  ', https://player.vimeo.com/video/76979871",
        // Q99: the other sites.
        "https://www.bilibili.com/video/BV1GJ411x7h7, https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0",
        "https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007, https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0",
        "https://m.bilibili.com/video/BV1GJ411x7h7, https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0",
        "https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0, https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0",
        "https://www.nicovideo.jp/watch/sm9, https://embed.nicovideo.jp/watch/sm9",
        "https://sp.nicovideo.jp/watch/sm9?ref=share, https://embed.nicovideo.jp/watch/sm9",
        "https://nico.ms/sm9, https://embed.nicovideo.jp/watch/sm9",
        "https://embed.nicovideo.jp/watch/sm9, https://embed.nicovideo.jp/watch/sm9",
        "https://www.dailymotion.com/video/x7tgad0, https://www.dailymotion.com/embed/video/x7tgad0",
        "https://dai.ly/x7tgad0, https://www.dailymotion.com/embed/video/x7tgad0",
        "https://www.dailymotion.com/embed/video/x7tgad0, https://www.dailymotion.com/embed/video/x7tgad0",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c, https://player.youku.com/embed/XNTk4MjQzMzQ0OA==",
        "https://player.youku.com/embed/XNTk4MjQzMzQ0OA==, https://player.youku.com/embed/XNTk4MjQzMzQ0OA==",
        "https://v.qq.com/x/page/x0029zdtwz6.html, https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6",
        "https://v.qq.com/x/cover/mzc00200abcdefg/x0029zdtwz6.html, https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6",
        "https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6, https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6",
    })
    void aVideoLinkIsShownAsItsPlayer(String url, String player) {
        assertThat(VideoEmbeds.player(url)).hasValue(player);
        assertThat(VideoEmbeds.sitePlayer(url)).hasValue(player);
        assertThat(VideoEmbeds.kind(player)).hasValue(VideoEmbeds.Kind.SITE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "", "  ", "not a link", "ftp://youtube.com/watch?v=dQw4w9WgXcQ", "javascript:alert(1)",
        // Not one video, or not these sites.
        "https://www.youtube.com/", "https://www.youtube.com/watch", "https://www.youtube.com/watch?v=short",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ%22onload", "https://www.youtube.com/playlist?list=PL123",
        "https://www.youtube.com/@channel", "https://youtu.be/", "https://vimeo.com/channels/staffpicks",
        "https://vimeo.com/76979871/extra", "https://vimeo.com/abc", "https://evil.example/watch?v=dQw4w9WgXcQ",
        "https://youtube.com.evil.example/watch?v=dQw4w9WgXcQ", "https://user@youtube.com/watch?v=dQw4w9WgXcQ",
        "https://youtube.com:8443/watch?v=dQw4w9WgXcQ",
        // Read as the App reads them: a trailing slash, and an escaped id, are not one video.
        "https://vimeo.com/76979871/", "https://www.youtube.com/embed/dQw4w9WgXcQ/",
        "https://www.youtube.com/watch/?v=dQw4w9WgXcQ", "https://www.youtube.com/embed/dQw4w9WgX%63Q",
        // Q99: not one video of the other sites.
        "https://www.bilibili.com/", "https://www.bilibili.com/video/av170001", "https://www.bilibili.com/video/BV1GJ411x7h",
        "https://space.bilibili.com/BV1GJ411x7h7", "https://b23.tv/BV1GJ411x7h7",
        "https://player.bilibili.com/player.html?aid=170001", "https://www.bilibili.com/video/BV1GJ411x7h7/x",
        "https://www.nicovideo.jp/watch/lv123", "https://www.nicovideo.jp/user/123", "https://nico.ms/",
        "https://www.dailymotion.com/playlist/x6hynp", "https://www.dailymotion.com/video/X7TGAD0",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==", "https://v.youku.com/v_show/id_<x>.html",
        "https://v.qq.com/x/page/x0029zdtwz6", "https://v.qq.com/x/cover/mzc00200abcdefg.html",
        "https://v.qq.com.evil.example/x/page/x0029zdtwz6.html", "https://evil.example/txp/iframe/player.html?vid=x0029zdtwz6",
    })
    void anyOtherAddressIsNoSitesPlayer(String url) {
        // F-S15: some of these are another page (any https address), which is no site's player.
        assertThat(VideoEmbeds.sitePlayer(url)).isEmpty();
        assertThat(VideoEmbeds.site(url)).isEmpty();
    }

    /** Q99 (D-Q99-2): only YouTube's and Vimeo's players say where they are, so only their videos can be watched. */
    @ParameterizedTest
    @CsvSource({
        "https://youtu.be/dQw4w9WgXcQ, true",
        "https://vimeo.com/76979871, true",
        "https://www.bilibili.com/video/BV1GJ411x7h7, false",
        "https://nico.ms/sm9, false",
        "https://dai.ly/x7tgad0, false",
        "https://player.youku.com/embed/XNTk4MjQzMzQ0OA==, false",
        "https://v.qq.com/x/page/x0029zdtwz6.html, false",
    })
    void onlyYoutubeAndVimeoSayHowMuchWasWatched(String url, boolean trackable) {
        assertThat(VideoEmbeds.trackable(VideoEmbeds.player(url).orElseThrow())).isEqualTo(trackable);
        assertThat(VideoEmbeds.trackablePlayer(url).isPresent()).isEqualTo(trackable);
        assertThat(VideoEmbeds.trackable(null)).isFalse();
    }

    @org.junit.jupiter.api.Test
    void nothingIsNoPlayer() {
        assertThat(VideoEmbeds.player(null)).isEmpty();
    }

    /**
     * F-S11: the site of a video link, as the parameter of the sites mainland China plays names it; none for a link
     * QuizBuks does not recognise. The App's {@code video.test.ts} has these cases.
     */
    @ParameterizedTest
    @CsvSource({
        "https://youtu.be/dQw4w9WgXcQ, youtube",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ, youtube",
        "https://vimeo.com/76979871, vimeo",
        "https://www.bilibili.com/video/BV1GJ411x7h7/?spm_id_from=333.1007, bilibili",
        "https://player.bilibili.com/player.html?bvid=BV1GJ411x7h7&autoplay=0, bilibili",
        "https://nico.ms/sm9, niconico",
        "https://dai.ly/x7tgad0, dailymotion",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c, youku",
        "https://player.youku.com/embed/XNTk4MjQzMzQ0OA==, youku",
        "https://v.qq.com/x/cover/mzc00200abcdefg/x0029zdtwz6.html, tencent",
        "https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6, tencent",
        "https://b23.tv/BV1GJ411x7h7, none",
        "https://video.example/capitals, none",
        "https://www.bilibili.com/, none",
        "https://v.qq.com.evil.example/x/page/x0029zdtwz6.html, none",
        "'', none",
    })
    void aVideoLinkHasItsSite(String url, String site) {
        assertThat(VideoEmbeds.site(url).map(VideoEmbeds.Site::key).orElse("none")).isEqualTo(site);
    }

    /**
     * F-S10: a YouTube Shorts link is a vertical video (the material's own check box marks the others). The App's
     * {@code video.test.ts} has these cases.
     */
    @ParameterizedTest
    @CsvSource({
        "https://www.youtube.com/shorts/dQw4w9WgXcQ, true",
        "https://m.youtube.com/shorts/dQw4w9WgXcQ?feature=share, true",
        "'  https://youtube.com/shorts/dQw4w9WgXcQ  ', true",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ, false",
        "https://youtu.be/dQw4w9WgXcQ, false",
        "https://www.youtube.com/embed/dQw4w9WgXcQ, false",
        "https://www.youtube.com/shorts/short, false",
        "https://www.youtube.com/shorts/dQw4w9WgXcQ/, false",
        "https://evil.example/shorts/dQw4w9WgXcQ, false",
        "https://vimeo.com/76979871, false",
        "https://www.bilibili.com/video/BV1GJ411x7h7, false",
        "'', false",
    })
    void aShortsLinkIsVertical(String url, boolean shorts) {
        assertThat(VideoEmbeds.shorts(url)).isEqualTo(shorts);
        assertThat(VideoEmbeds.shorts(null)).isFalse();
    }

    /**
     * F-S15: a video link on any https address: a video file (its path ends with .mp4, .webm or .mov; the query and
     * fragment do not count) plays in the pages' own {@code <video>} and says where it is; any other https address is a
     * page framed in a sandbox and does not. The address is the link with its scheme and host in lower case. The App's
     * and the console's {@code video.test.ts} have these cases.
     */
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4|FILE|https://videos.staging.quizbuks.com/quizbuks-demo-30s.mp4",
        "'  https://cdn.example.com/a/b/clip.webm  '|FILE|https://cdn.example.com/a/b/clip.webm",
        "HTTPS://CDN.Example.com/Clip.MOV|FILE|https://cdn.example.com/Clip.MOV",
        "https://cdn.example.com/clip.mp4?sig=abc&exp=1|FILE|https://cdn.example.com/clip.mp4?sig=abc&exp=1",
        "https://cdn.example.com/clip.mp4#t=10|FILE|https://cdn.example.com/clip.mp4#t=10",
        "https://cdn.example.com/%E5%BE%AE.mp4|FILE|https://cdn.example.com/%E5%BE%AE.mp4",
        "https://video.example/capitals|PAGE|https://video.example/capitals",
        "https://Player.Example.com/embed/123?autoplay=0|PAGE|https://player.example.com/embed/123?autoplay=0",
        "https://evil.example/watch?v=dQw4w9WgXcQ|PAGE|https://evil.example/watch?v=dQw4w9WgXcQ",
        "https://cdn.example.com/clip.mp4/|PAGE|https://cdn.example.com/clip.mp4/",
        "https://cdn.example.com|PAGE|https://cdn.example.com",
        "https://www.youtube.com/watch?v=dQw4w9WgXcQ|SITE|https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ",
        "https://v.qq.com/x/page/x0029zdtwz6.html|SITE|https://v.qq.com/txp/iframe/player.html?vid=x0029zdtwz6",
    })
    void aLinkOnAnyAddressIsAFileOrAPage(String url, String kind, String player) {
        assertThat(VideoEmbeds.player(url)).hasValue(player);
        assertThat(VideoEmbeds.kind(player)).hasValue(VideoEmbeds.Kind.valueOf(kind));
        assertThat(VideoEmbeds.trackable(player)).isEqualTo(kind.equals("FILE") || player.contains("youtube"));
        assertThat(VideoEmbeds.trackablePlayer(url).isPresent()).isEqualTo(VideoEmbeds.trackable(player));
        // The address played is read again the same way.
        assertThat(VideoEmbeds.player(player)).hasValue(player);
        if (!kind.equals("SITE")) {
            assertThat(VideoEmbeds.site(url)).isEmpty();
            assertThat(VideoEmbeds.host(url)).hasValue(java.net.URI.create(player).getHost());
        }
    }

    /** F-S15: not https, a user or a port, no host name, dot or empty segments, other characters: no video. */
    @ParameterizedTest
    @ValueSource(strings = {
        "http://cdn.example.com/clip.mp4", "http://video.example/capitals", "ftp://cdn.example.com/clip.mp4",
        "https://user@cdn.example.com/clip.mp4", "https://user:pw@video.example/capitals",
        "https://cdn.example.com:8443/clip.mp4", "https://video.example:443/capitals",
        "https://203.0.113.7/clip.mp4", "https://localhost/clip.mp4", "https://-cdn.example.com/clip.mp4",
        "https://cdn.example.com/a/../clip.mp4", "https://cdn.example.com/a/./clip.mp4",
        "https://cdn.example.com//clip.mp4", "https://cdn.example.com/a b.mp4", "https://cdn.example.com/a\"b.mp4",
        "https://cdn.example.com/<script>.mp4", "https://cdn.example.com/a%2.mp4", "https://cdn.example.com/a\\b.mp4",
        "//cdn.example.com/clip.mp4", "cdn.example.com/clip.mp4", "javascript:alert(1)", "", "   ",
        // On the seven sites' hosts, an address that is not one video is none (channels, short links).
        "https://www.youtube.com/@channel", "https://www.youtube.com/", "https://youtu.be/",
        "https://vimeo.com/channels/staffpicks", "https://b23.tv/BV1GJ411x7h7", "https://www.bilibili.com/",
        "https://www.youtube.com/clip.mp4",
    })
    void anyOtherAddressIsNoVideo(String url) {
        assertThat(VideoEmbeds.player(url)).isEmpty();
        assertThat(VideoEmbeds.filePlayer(url)).isEmpty();
        assertThat(VideoEmbeds.pagePlayer(url)).isEmpty();
        assertThat(VideoEmbeds.kind(url)).isEmpty();
    }
}
