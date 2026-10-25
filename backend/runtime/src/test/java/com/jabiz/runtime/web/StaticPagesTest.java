package com.jabiz.runtime.web;

import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** The pages of a static site are found on the classpath once (src/test/resources/static/site/**, phase 16zf-b). */
class StaticPagesTest {

    private static final Spa SITE = new Spa("/site", null, null);
    private static final Spa APP = new Spa("/app", null, null, "app", null);

    @Test
    void findsEveryDirectoryPageOfASiteWithoutAnEntry() {
        var pages = new StaticPages(new JabizWebProperties(List.of(SITE, APP)));
        assertThat(pages.pageFor(SITE, "/site/zh")).contains("/site/zh/index.html");
        assertThat(pages.pageFor(SITE, "/site/zh/")).contains("/site/zh/index.html");
        assertThat(pages.pageFor(SITE, "/site/zh/pricing/")).contains("/site/zh/pricing/index.html");
        assertThat(pages.pageFor(SITE, "/site/ja")).contains("/site/ja/index.html");
        // The root is the SPA's own index; an unknown directory falls back to it.
        assertThat(pages.pageFor(SITE, "/site")).isEmpty();
        assertThat(pages.pageFor(SITE, "/site/")).isEmpty();
        assertThat(pages.pageFor(SITE, "/site/zh/unknown")).isEmpty();
        // An SPA with an entry never has directory pages, whatever the jar holds.
        assertThat(pages.pageFor(APP, "/app/zh")).isEmpty();
    }

    /** A site at the root sees every SPA's files: it owns only the directories no other SPA owns, never the root. */
    @Test
    void aSiteAtTheRootOwnsOnlyItsOwnDirectories() {
        var root = new Spa("/", null, null);
        var properties = new JabizWebProperties(List.of(root, APP, new Spa("/site", null, null, "portal", null)));
        var pages = new StaticPages(properties);
        // src/test/resources/static/site/** belongs to the /site SPA (an entry SPA here), not to the root.
        assertThat(pages.pageFor(root, "/site/zh")).isEmpty();
        assertThat(pages.pageFor(root, "/")).isEmpty();
        assertThat(pages.pageFor(root, "")).isEmpty();
    }

    @Test
    void withoutASiteThereIsNothingToServe() {
        assertThat(new StaticPages(new JabizWebProperties(List.of(APP))).pageFor(APP, "/app/x")).isEmpty();
        assertThat(StaticPages.none().pageFor(SITE, "/site/zh")).isEmpty();
    }

    /** The directory of a page's location, for an exploded classpath, a jar and a nested Boot jar alike. */
    @ParameterizedTest
    @CsvSource(nullValues = "-", value = {
        "file:/work/build/resources/main/static/biz/zh/index.html, /biz/zh",
        "jar:file:/opt/app.jar!/BOOT-INF/classes!/static/biz/zh/pricing/index.html, /biz/zh/pricing",
        "jar:nested:/opt/app.jar/!BOOT-INF/classes/!/static/biz/ja/index.html, /biz/ja",
        "file:/C:/work/static/biz/zh/index.html, /biz/zh",
        "file:/work/static/biz/index.html, -",
        "file:/work/static/biz/v1.0/index.html, -",
        "file:/work/static/biz/zh/page.html, -",
        "file:/work/static/other/zh/index.html, -",
    })
    void theDirectoryOfAPageIsItsRequestPath(String location, String directory) {
        var biz = new Spa("/biz", null, null);
        var properties = new JabizWebProperties(List.of(biz, APP));
        assertThat(StaticPages.directoryOf(location, biz, properties)).isEqualTo(Optional.ofNullable(directory));
    }

    /** At the root, another SPA's pages are that SPA's, the rest the root's. */
    @Test
    void atTheRootOtherSpasPagesAreLeftToThem() {
        var root = new Spa("/", null, null);
        var properties = new JabizWebProperties(List.of(root, APP));
        assertThat(StaticPages.directoryOf("file:/w/static/app/zh/index.html", root, properties)).isEmpty();
        assertThat(StaticPages.directoryOf("file:/w/static/about/index.html", root, properties)).contains("/about");
        assertThat(StaticPages.directoryOf("file:/w/static/index.html", root, properties)).isEmpty();
    }
}
