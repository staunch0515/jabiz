package com.jabiz.runtime.web;

import com.jabiz.runtime.web.JabizWebProperties.Spa;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The pages of a static site (phase 16zf-b): an SPA without a sign-in entry may hold several pages, one
 * {@code index.html} per directory ({@code /biz/zh/index.html}, {@code /biz/zh/pricing/index.html}), and a request for
 * such a directory is answered with its own page instead of the site's root page. The directories are found once at
 * startup from what the jar holds under {@code static/<prefix>/}, per site, so that no request touches the classpath.
 * Only directories the site owns count (another SPA's pages under a site at {@code /} belong to that SPA), and a
 * directory whose name has a dot is never a client route, so it is left out.
 */
@Component
public class StaticPages {

    /** The directories with a page of their own ({@code /biz/zh}, no trailing slash), by the site's path. */
    private final Map<String, Set<String>> directories;

    @Autowired
    public StaticPages(JabizWebProperties properties) {
        this(scan(properties));
    }

    /** With the directories given by site path, for tests. */
    StaticPages(Map<String, Set<String>> directories) {
        Map<String, Set<String>> copy = new HashMap<>();
        directories.forEach((path, dirs) -> copy.put(path, Set.copyOf(dirs)));
        this.directories = Map.copyOf(copy);
    }

    /** No pages at all: every route of every SPA is answered with its index. */
    public static StaticPages none() {
        return new StaticPages(Map.of());
    }

    /**
     * The page of a request path within {@code spa}: {@code <directory>/index.html} when the path (with or without a
     * trailing slash) is a directory of the site with a page of its own; empty for the site's root and for anything
     * else (the caller falls back to the site's index).
     */
    public Optional<String> pageFor(Spa spa, String requestPath) {
        Set<String> own = spa.entry() == null ? directories.get(spa.path()) : null;
        if (own == null || own.isEmpty()) {
            return Optional.empty();
        }
        String directory = requestPath.endsWith("/") ? requestPath.substring(0, requestPath.length() - 1) : requestPath;
        if (directory.equals(spa.path()) || !own.contains(directory)) {
            return Optional.empty();
        }
        return Optional.of(directory + "/index.html");
    }

    /**
     * The directory of a page's classpath location within the site at {@code spa}, as a request path
     * ({@code /biz/zh}); empty for the site's own index, a page another SPA owns, or a directory that is no route.
     */
    static Optional<String> directoryOf(String location, Spa spa, JabizWebProperties properties) {
        String normalized = location.replace('\\', '/');
        int at = normalized.lastIndexOf("/static" + spa.base());
        if (at < 0 || !normalized.endsWith("/index.html")) {
            return Optional.empty();
        }
        String path = normalized.substring(at + "/static".length());
        String directory = path.substring(0, path.length() - "/index.html".length());
        if (directory.isEmpty() || directory.equals(spa.path()) || directory.contains(".")) {
            return Optional.empty();
        }
        // A site at / sees every SPA's pages: only the directories no other SPA owns are its own.
        Spa owner = properties.spaFor(directory).orElse(null);
        if (owner == null || !owner.path().equals(spa.path())) {
            return Optional.empty();
        }
        return Optional.of(directory);
    }

    private static Map<String, Set<String>> scan(JabizWebProperties properties) {
        Map<String, Set<String>> found = new HashMap<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (Spa spa : properties.spa()) {
            if (spa.path() == null || spa.entry() != null) {
                continue;
            }
            Set<String> own = new HashSet<>();
            try {
                for (Resource resource : resolver.getResources("classpath*:static" + spa.base() + "**/index.html")) {
                    directoryOf(resource.getURL().toString(), spa, properties).ifPresent(own::add);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Could not list the pages of the site at " + spa.path(), e);
            }
            if (!own.isEmpty()) {
                found.put(spa.path(), own);
            }
        }
        return found;
    }
}
