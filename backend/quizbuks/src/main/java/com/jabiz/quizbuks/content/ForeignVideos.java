package com.jabiz.quizbuks.content;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Whether a version of a quiz has a video mainland China cannot play (F-S11, docs/quizbuks/plans/impl/F-S11.md;
 * Q106 §2.1): a video link whose site ({@link VideoEmbeds#site}) is not one of the sites the controlled parameter
 * {@code qb.video.mainland-accessible-sites} lists, and any video link of a site QuizBuks does not recognise (a
 * material's link may be any address: rather show it to fewer takers than one they cannot open). Every language counts:
 * the base's materials and each other language's own (Q73). A blank link is no video.
 *
 * <p>F-S15: the parameter may also list host names (such as the platform's own video server): a video file's or another
 * page's link on a listed host is not foreign ({@link Accessible}). Nothing is built in: headquarters registers a host
 * through the controlled change.
 *
 * <p>Only the links: a publication's shared video (F-S12) joins the version's links where the card is frozen
 * ({@code PublicationEditing.foreignVideo(ctx, snapshot, shared)}).
 * The App's and the console's {@code qb/video.ts} ({@code foreignVideo}) read links the same way, with the same cases.
 */
public final class ForeignVideos {

    /** The parameter's first value: the sites mainland China plays, as {@link VideoEmbeds.Site#key} names them. */
    public static final String DEFAULT_SITES = "bilibili,youku,tencent";

    /** A host name in the parameter (as {@code VideoEmbeds} takes hosts): labels, at least two, the last not a number. */
    private static final java.util.regex.Pattern HOST = java.util.regex.Pattern.compile(
        "(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?");

    private ForeignVideos() {
    }

    /**
     * The sites of a parameter's value (comma-separated keys; unknown keys are ignored); the default's when there is
     * no value yet (before the setup's proposal was published).
     */
    public static Accessible sites(String value) {
        String given = value == null ? DEFAULT_SITES : value;
        Set<VideoEmbeds.Site> sites = EnumSet.noneOf(VideoEmbeds.Site.class);
        Set<String> hosts = new java.util.LinkedHashSet<>();
        for (String key : given.split(",")) {
            Optional<VideoEmbeds.Site> site = VideoEmbeds.Site.ofKey(key);
            if (site.isPresent()) {
                sites.add(site.get());
            } else if (HOST.matcher(key.strip().toLowerCase(Locale.ROOT)).matches()) {
                hosts.add(key.strip().toLowerCase(Locale.ROOT));
            }
        }
        return new Accessible(sites, hosts);
    }

    /**
     * What mainland China plays (F-S11, F-S15): the sites the parameter names by key, and the hosts it names (a video
     * file's or another page's link on one of them).
     */
    public record Accessible(Set<VideoEmbeds.Site> sites, Set<String> hosts) {
        public Accessible {
            sites = sites.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(sites));
            hosts = Set.copyOf(hosts);
        }

        /** Only these sites, no host. */
        public static Accessible of(Set<VideoEmbeds.Site> sites) {
            return new Accessible(sites, Set.of());
        }

        /** Whether a video link plays in mainland China: its site is listed, or (a file, a page) its host. */
        public boolean plays(String url) {
            Optional<VideoEmbeds.Site> site = VideoEmbeds.site(url);
            if (site.isPresent()) {
                return sites.contains(site.get());
            }
            return VideoEmbeds.sitePlayer(url).isEmpty() && VideoEmbeds.player(url).isPresent()
                && VideoEmbeds.host(url).map(hosts::contains).orElse(false);
        }
    }

    /** Whether a version's snapshot has a foreign video, in any of its languages. */
    public static boolean foreign(QuizSnapshot snapshot, Accessible accessible) {
        return snapshot != null && foreign(videoLinks(snapshot).stream(), accessible);
    }

    /** Whether any of these video links is foreign: of a site not in {@code accessible}, or of none recognised. */
    public static boolean foreign(Stream<String> videoLinks, Accessible accessible) {
        return videoLinks.filter(url -> url != null && !url.isBlank()).anyMatch(url -> !accessible.plays(url));
    }

    /** The links of a snapshot's video materials, the base's and every other language's own. */
    public static List<String> videoLinks(QuizSnapshot snapshot) {
        Stream<QuizSnapshot.Material> base = snapshot.materials().stream();
        Stream<QuizSnapshot.Material> others = snapshot.translations().values().stream()
            .flatMap(language -> language.materials().stream());
        return Stream.concat(base, others).filter(material -> MaterialKind.VIDEO_LINK.name().equals(material.kind()))
            .map(QuizSnapshot.Material::url).filter(url -> url != null && !url.isBlank()).toList();
    }

    /** {@link #foreign(Stream, Accessible)} of a collection. */
    public static boolean foreign(Collection<String> videoLinks, Accessible accessible) {
        return foreign(videoLinks.stream(), accessible);
    }
}
