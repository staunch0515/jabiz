package com.jabiz.quizbuks.content.video;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Q99b: which addresses are short links, and which redirects lead to one Bilibili video. */
class ShortVideoLinksTest {

    @Test
    void aShortLinkIsB23WithItsCode() {
        assertThat(ShortVideoLinks.code("https://b23.tv/bMyOKXv")).contains("bMyOKXv");
        assertThat(ShortVideoLinks.code(" https://b23.tv/bMyOKXv/ ")).contains("bMyOKXv");
        assertThat(ShortVideoLinks.code("http://www.b23.tv/bMyOKXv")).contains("bMyOKXv");
        assertThat(ShortVideoLinks.code("https://b23.tv/")).isEmpty();
        assertThat(ShortVideoLinks.code("https://b23.tv/a/b")).isEmpty();
        assertThat(ShortVideoLinks.code("https://b23.tv:8443/bMyOKXv")).isEmpty();
        assertThat(ShortVideoLinks.code("https://user@b23.tv/bMyOKXv")).isEmpty();
        assertThat(ShortVideoLinks.code("https://evil.example/b23.tv/bMyOKXv")).isEmpty();
        assertThat(ShortVideoLinks.code("ftp://b23.tv/bMyOKXv")).isEmpty();
        assertThat(ShortVideoLinks.code(null)).isEmpty();
    }

    @Test
    void aRedirectToOneBilibiliVideoGivesItsPlainAddress() {
        assertThat(ShortVideoLinks.videoOf("https://www.bilibili.com/video/BV1GJ411x7h7?share_source=copy_web&vd_source=x"))
            .contains("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertThat(ShortVideoLinks.videoOf("https://m.bilibili.com/video/BV1GJ411x7h7/?p=1"))
            .contains("https://www.bilibili.com/video/BV1GJ411x7h7");
        assertThat(ShortVideoLinks.videoOf("https://space.bilibili.com/12345")).isEmpty();
        assertThat(ShortVideoLinks.videoOf("https://www.bilibili.com/bangumi/play/ep1")).isEmpty();
        assertThat(ShortVideoLinks.videoOf("http://www.bilibili.com/video/BV1GJ411x7h7")).isEmpty();
        assertThat(ShortVideoLinks.videoOf("https://www.bilibili.com.evil.example/video/BV1GJ411x7h7")).isEmpty();
        assertThat(ShortVideoLinks.videoOf(null)).isEmpty();
    }
}
