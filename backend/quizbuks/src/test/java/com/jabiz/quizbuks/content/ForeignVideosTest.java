package com.jabiz.quizbuks.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F-S11: which versions have a video mainland China cannot play. The App's and the console's {@code video.test.ts}
 * have the cases of {@link #aLinkIsForeignUnlessItsSiteIsListed}.
 */
class ForeignVideosTest {

    private static final Set<VideoEmbeds.Site> MAINLAND = ForeignVideos.sites(ForeignVideos.DEFAULT_SITES);

    @ParameterizedTest
    @CsvSource({
        "https://youtu.be/dQw4w9WgXcQ, true",
        "https://vimeo.com/76979871, true",
        "https://nico.ms/sm9, true",
        "https://dai.ly/x7tgad0, true",
        "https://www.bilibili.com/video/BV1GJ411x7h7, false",
        "https://v.youku.com/v_show/id_XNTk4MjQzMzQ0OA==.html?spm=a2h0c, false",
        "https://v.qq.com/x/page/x0029zdtwz6.html, false",
        // Not recognised: foreign, a mainland site's address that is not one video too.
        "https://video.example/capitals, true",
        "https://b23.tv/BV1GJ411x7h7, true",
        "https://www.bilibili.com/, true",
        // Blank: no video.
        "'', false",
        "'   ', false",
    })
    void aLinkIsForeignUnlessItsSiteIsListed(String url, boolean foreign) {
        assertThat(ForeignVideos.foreign(List.of(url), MAINLAND)).isEqualTo(foreign);
    }

    @Test
    void theParameterNamesTheSitesAndIgnoresWhatItDoesNotKnow() {
        assertThat(MAINLAND).containsExactlyInAnyOrder(VideoEmbeds.Site.BILIBILI, VideoEmbeds.Site.YOUKU,
            VideoEmbeds.Site.TENCENT);
        assertThat(ForeignVideos.sites(null)).isEqualTo(MAINLAND);
        assertThat(ForeignVideos.sites(" YouTube , nothing,,bilibili")).containsExactlyInAnyOrder(
            VideoEmbeds.Site.YOUTUBE, VideoEmbeds.Site.BILIBILI);
        assertThat(ForeignVideos.sites("")).isEmpty();
        // A site taken off the list makes its videos foreign; one added, not.
        assertThat(ForeignVideos.foreign(List.of("https://v.qq.com/x/page/x0029zdtwz6.html"),
            EnumSet.of(VideoEmbeds.Site.BILIBILI))).isTrue();
        assertThat(ForeignVideos.foreign(List.of("https://youtu.be/dQw4w9WgXcQ"),
            EnumSet.of(VideoEmbeds.Site.YOUTUBE))).isFalse();
    }

    @Test
    void aVersionIsForeignByAnyVideoInAnyLanguage() {
        QuizSnapshot.Material bilibili = video(1, "https://www.bilibili.com/video/BV1GJ411x7h7");
        QuizSnapshot.Material youtube = video(1, "https://youtu.be/dQw4w9WgXcQ");
        QuizSnapshot.Material link = new QuizSnapshot.Material(2, "LINK", "L", null, null,
            "https://youtu.be/dQw4w9WgXcQ", null, null, List.of());
        // Only video links count: a plain link to YouTube is a link.
        assertThat(ForeignVideos.foreign(snapshot(List.of(bilibili, link), Map.of()), MAINLAND)).isFalse();
        assertThat(ForeignVideos.foreign(snapshot(List.of(youtube), Map.of()), MAINLAND)).isTrue();
        assertThat(ForeignVideos.foreign(snapshot(List.of(), Map.of()), MAINLAND)).isFalse();
        // Q73: another language's own video counts as the base's does.
        QuizSnapshot.Language en = new QuizSnapshot.Language(Map.of(), Map.of(), List.of(youtube));
        assertThat(ForeignVideos.foreign(snapshot(List.of(bilibili), Map.of("en", en)), MAINLAND)).isTrue();
        assertThat(ForeignVideos.videoLinks(snapshot(List.of(bilibili, link), Map.of("en", en))))
            .containsExactly(bilibili.url(), youtube.url());
        assertThat(ForeignVideos.foreign((QuizSnapshot) null, MAINLAND)).isFalse();
    }

    private static QuizSnapshot.Material video(int no, String url) {
        return new QuizSnapshot.Material(no, "VIDEO_LINK", "V", null, null, url, null, null, List.of());
    }

    private static QuizSnapshot snapshot(List<QuizSnapshot.Material> materials, Map<String, QuizSnapshot.Language> languages) {
        return new QuizSnapshot("T", null, null, null, false, materials, Drafts.complete().questions(), false,
            languages);
    }
}
