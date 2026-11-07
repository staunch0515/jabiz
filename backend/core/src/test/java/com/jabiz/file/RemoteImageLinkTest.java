package com.jabiz.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteImageLinkTest {

    private static final Set<Integer> HTTPS = Set.of();

    private static RemoteImageLink parse(String text) {
        return RemoteImageLink.parse(text, HTTPS);
    }

    private static void refused(String text, String code) {
        assertThatThrownBy(() -> parse(text)).as(text).isInstanceOfSatisfying(RemoteImageLink.Refused.class,
            refused -> {
                assertThat(refused.code()).isEqualTo(code);
                // The message never repeats the link.
                assertThat(refused.getMessage()).doesNotContain("secret").doesNotContain("example");
            });
    }

    @Test
    void aPlainLinkGivesHostPortAndRequestTarget() {
        RemoteImageLink link = parse("  HTTPS://Images.Example.COM/a/b%20c.png?sig=secret&x=1#frag ");
        assertThat(link.host()).isEqualTo("images.example.com");
        assertThat(link.port()).isEqualTo(443);
        assertThat(link.requestTarget()).isEqualTo("/a/b%20c.png?sig=secret&x=1");
        assertThat(link.literal()).isFalse();
        assertThat(link.hostHeader()).isEqualTo("images.example.com");
        assertThat(link.uri().toString()).isEqualTo("https://images.example.com/a/b%20c.png?sig=secret&x=1");
        assertThat(link.shown()).isEqualTo("https://images.example.com/a/b%20c.png?…");
        assertThat(link.toString()).doesNotContain("secret");
    }

    @Test
    void emptyPathsBecomeTheRootAndTheQueryIsKept() {
        assertThat(parse("https://example.com").requestTarget()).isEqualTo("/");
        assertThat(parse("https://example.com?x=1").requestTarget()).isEqualTo("/?x=1");
        assertThat(parse("https://example.com.").host()).isEqualTo("example.com");
    }

    @Test
    void nonAsciiHostsAndPathsAreSentInAscii() {
        RemoteImageLink link = parse("https://bücher.example/写真.png");
        assertThat(link.host()).isEqualTo("xn--bcher-kva.example");
        assertThat(link.requestTarget()).isEqualTo("/%E5%86%99%E7%9C%9F.png");
    }

    @Test
    void portsMustBeAllowed() {
        assertThat(parse("https://example.com:443/a").port()).isEqualTo(443);
        refused("https://example.com:8443/a", RemoteImageLink.LINK_INVALID);
        refused("https://example.com:22/a", RemoteImageLink.LINK_INVALID);
        refused("https://example.com:/a", RemoteImageLink.LINK_INVALID);
        refused("https://example.com:99999/a", RemoteImageLink.LINK_INVALID);
        refused("https://example.com:+443/a", RemoteImageLink.LINK_INVALID);
        RemoteImageLink other = RemoteImageLink.parse("https://example.com:8443/a", Set.of(443, 8443));
        assertThat(other.port()).isEqualTo(8443);
        assertThat(other.hostHeader()).isEqualTo("example.com:8443");
        assertThat(other.shown()).isEqualTo("https://example.com:8443/a");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a.png", "ftp://example.com/a.png", "file:///etc/passwd",
        "gopher://example.com/", "data:image/png;base64,AAAA", "javascript:alert(1)", "HTTP://example.com/"})
    void onlyHttpsIsFollowed(String text) {
        refused(text, RemoteImageLink.NOT_HTTPS);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "example.com/a.png", "/a.png", "//example.com/a.png", "https:example.com/a",
        "https:/example.com/a", "https:///a", "https://user:secret@example.com/a", "https://user@example.com/a",
        "https://example.com@evil.example/a", "https://exa mple.com/", "https://example.com/a b",
        "https://example.com\\@evil.example/", "https://example.com/\u0000", "https://example.com/a\tb",
        "https://localhost/a", "https://intranet/a", "https://127.1/a", "https://2130706433/a",
        "https://0x7f000001/a", "https://0177.0.0.1/a", "https://127.000.000.001/a", "https://1.2.3.04/a",
        "https://10.1/a", "https://exa_mple.com/a", "https://-example.com/a", "https://example-.com/a",
        "https://ex..ample.com/a", "https://[::1/a", "https://[fe80::1%25eth0]/a", "https://[example.com]/a",
        "https://[::1]x/a", "https:// example.com/", "https://example.com/%zz", "9https://example.com/",
        "https://xn--a.example/a　b"})
    void malformedOrUnusualLinksAreRefused(String text) {
        refused(text, RemoteImageLink.LINK_INVALID);
    }

    @Test
    void tooLongLinksAreRefused() {
        refused("https://example.com/" + "a".repeat(RemoteImageLink.MAX_LENGTH), RemoteImageLink.LINK_INVALID);
        refused(null, RemoteImageLink.LINK_INVALID);
        refused("https://" + "a".repeat(250) + ".example/", RemoteImageLink.LINK_INVALID);
    }

    @Test
    void ipAddressesInTheirUsualNotationAreLiteralsJudgedLater() {
        RemoteImageLink v4 = parse("https://93.184.216.34/a.png");
        assertThat(v4.literal()).isTrue();
        assertThat(v4.host()).isEqualTo("93.184.216.34");
        RemoteImageLink v6 = parse("https://[2606:4700::1111]/a.png");
        assertThat(v6.literal()).isTrue();
        assertThat(v6.host()).isEqualTo("2606:4700::1111");
        assertThat(v6.hostHeader()).isEqualTo("[2606:4700::1111]");
        assertThat(v6.uri().getHost()).isEqualTo("[2606:4700::1111]");
        assertThat(parse("https://[::ffff:127.0.0.1]/").host()).isEqualTo("::ffff:127.0.0.1");
        // The address classification decides about these; the link itself is well-formed.
        assertThat(parse("https://127.0.0.1/").literal()).isTrue();
    }

    @Test
    void redirectsAreResolvedAgainstTheLinkAndCheckedAgain() {
        RemoteImageLink link = parse("https://example.com/dir/a.png?sig=1");
        assertThat(link.redirect("b.png", HTTPS).requestTarget()).isEqualTo("/dir/b.png");
        assertThat(link.redirect("/c.png?x=2", HTTPS).requestTarget()).isEqualTo("/c.png?x=2");
        assertThat(link.redirect("../d.png", HTTPS).requestTarget()).isEqualTo("/d.png");
        RemoteImageLink other = link.redirect("//cdn.example.net/e.png", HTTPS);
        assertThat(other.host()).isEqualTo("cdn.example.net");
        assertThat(link.redirect("https://cdn.example.net/f.png#x", HTTPS).requestTarget()).isEqualTo("/f.png");
        assertThatThrownBy(() -> link.redirect("http://example.com/a.png", HTTPS))
            .isInstanceOfSatisfying(RemoteImageLink.Refused.class,
                refused -> assertThat(refused.code()).isEqualTo(RemoteImageLink.NOT_HTTPS));
        for (String location : new String[] {"https://u:p@example.com/", "https://example.com:8080/", "",
            "https://exa mple.com/", "https://127.1/", "https://[::1"}) {
            assertThatThrownBy(() -> link.redirect(location, HTTPS)).as(location)
                .isInstanceOfSatisfying(RemoteImageLink.Refused.class,
                    refused -> assertThat(refused.code()).isEqualTo(RemoteImageLink.LINK_INVALID));
        }
        assertThatThrownBy(() -> link.redirect(null, HTTPS)).isInstanceOf(RemoteImageLink.Refused.class);
    }

    @Test
    void onlyCheckedPartsMakeALink() {
        assertThat(new RemoteImageLink("example.com", 443, "/a?b", false).shown()).isEqualTo("https://example.com/a?…");
        for (Runnable bad : List.<Runnable>of(
            () -> new RemoteImageLink("example.com\r\nX: y", 443, "/", false),
            () -> new RemoteImageLink("", 443, "/", false),
            () -> new RemoteImageLink("a@example.com", 443, "/", false),
            () -> new RemoteImageLink("example.com", 443, "a b", false),
            () -> new RemoteImageLink("example.com", 443, "/a b", false),
            () -> new RemoteImageLink("example.com", 0, "/", false))) {
            assertThatThrownBy(bad::run).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void shownLinksHaveNoQueryNorCredentials() {
        assertThat(RemoteImageLink.shown("https://u:p@example.com/a.png?token=secret#f"))
            .isEqualTo("https://example.com/a.png?…");
        assertThat(RemoteImageLink.shown("https://example.com/a.png")).isEqualTo("https://example.com/a.png");
        assertThat(RemoteImageLink.shown("http://example.com:8080")).isEqualTo("http://example.com:8080");
        assertThat(RemoteImageLink.shown("not a link")).isEqualTo("***");
        assertThat(RemoteImageLink.shown("a.png?x=1")).isEqualTo("***");
        assertThat(RemoteImageLink.shown(null)).isNull();
    }
}
