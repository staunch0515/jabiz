package com.jabiz.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/**
 * The `jabizApp { }` block of a deployable application (docs/design/17-apps-and-branches.md section 3.1).
 *
 * ```
 * jabizApp {
 *     mainClass = "com.jabiz.app.App"
 *     spa("/", "../../frontend")          // built with VITE_BASE=/        → static/
 *     spa("/admin", "../../frontend")     // built with VITE_BASE=/admin/  → static/admin/
 *     spa("/", "../../frontend", extension = "admin-extension")   // with the application's own admin pages
 *     spa("/portal", "portal", entry = "portal", pwa = Pwa.ON)     // an application's own SPA, a PWA (decision D43)
 *     languages("en")                     // interface languages: a subset of zh, ja, en (default all)
 *     region = "en-US"                    // dates, numbers and amounts shown as in this region
 * }
 * ```
 */
open class JabizAppExtension internal constructor(
    objects: ObjectFactory,
    private val register: (SpaSpec) -> Unit,
) {

    /** The Spring Boot application class; also what `platformCheck` starts. */
    val mainClass: Property<String> = objects.property(String::class.java)

    /** The OpenAPI snapshot the admin frontend's types are generated from (docs/design/12-frontend.md section 3). */
    val openApiSnapshot: RegularFileProperty = objects.fileProperty()

    /**
     * The snapshot of the public templates' catalog that public frontends generate their types from
     * (docs/design/15-public-access.md section 7). An application branch points it into its own directory.
     */
    val publicQueriesSnapshot: RegularFileProperty = objects.fileProperty()

    /**
     * The interface languages (decision D22 item 7), in any order: the admin frontend offers only these and the
     * server requires messages only in these. Empty: all platform languages.
     */
    val languages: ListProperty<String> = objects.listProperty(String::class.java).convention(emptyList())

    /** The region dates, numbers and amounts are shown in (`en-US`); absent: the platform's neutral forms. */
    val region: Property<String> = objects.property(String::class.java)

    private val paths = mutableSetOf<String>()

    /** The SPAs declared so far, in declaration order. */
    internal val spas = mutableListOf<SpaSpec>()

    /** Sets [languages], each one of the platform's. */
    fun languages(vararg codes: String) {
        require(codes.isNotEmpty()) { "languages() needs at least one language" }
        val unknown = codes.filterNot { it in PLATFORM_LANGUAGES }
        require(unknown.isEmpty()) { "languages $unknown are not platform languages $PLATFORM_LANGUAGES" }
        languages.set(codes.distinct())
    }

    /**
     * Packages the single-page application built from [sourceDir] (a pnpm project, relative to this module) under
     * the URL prefix [path]: `/` or a prefix such as `/admin` (leading slash, no trailing slash). [extension] names
     * the directory (relative to this module) of the application's own admin pages, compiled into the admin
     * frontend (docs/design/12-frontend.md section 9, decision D22). [entry] is the sign-in entry the SPA signs in to
     * (`VITE_JABIZ_ENTRY`; the platform frontend's default is `admin`), [pwa] whether it is a progressive web app
     * (decision D43 item 6). The SPAs with their entries and PWA modes are also written into the jar as the server's
     * default `jabiz.web.spa` (docs/design/17-apps-and-branches.md section 3.2).
     */
    fun spa(path: String, sourceDir: String, extension: String? = null, entry: String? = null, pwa: Pwa = Pwa.OFF) {
        require(PATH.matches(path)) { "SPA path must be \"/\" or like \"/admin\", was \"$path\"" }
        require(paths.add(path)) { "SPA path \"$path\" is declared twice" }
        require(extension == null || extension.isNotBlank()) { "SPA extension directory must not be blank" }
        require(entry == null || ENTRY.matches(entry)) {
            "SPA entry must be the name of a sign-in entry (lower-case letters, digits, hyphens), was \"$entry\""
        }
        val spec = SpaSpec(path, sourceDir, extension, entry, pwa)
        spas += spec
        register(spec)
    }

    private companion object {
        val PATH = Regex("/|(/[a-z0-9][a-z0-9-]*)+")
        val ENTRY = Regex("[a-z][a-z0-9-]{0,39}")
        val PLATFORM_LANGUAGES = listOf("zh", "ja", "en")
    }
}

/** Whether an SPA is a progressive web app (decision D43 item 6; `jabiz.web.spa[i].pwa`). */
enum class Pwa {
    /** No manifest, no Service Worker. */
    OFF,

    /** A manifest and a Service Worker caching the application's shell. */
    ON,

    /** A Service Worker that unregisters itself and deletes its caches: how an installed PWA is taken back. */
    REMOVE;

    /** As the frontend build (`VITE_JABIZ_PWA`) and the server's configuration write it. */
    val value: String get() = name.lowercase()
}

/**
 * One SPA of an application: its URL prefix, the pnpm project it is built from, for the admin frontend the
 * directory of the application's own pages, the sign-in entry it signs in to and whether it is a PWA.
 */
data class SpaSpec(
    val path: String,
    val sourceDir: String,
    val extension: String? = null,
    val entry: String? = null,
    val pwa: Pwa = Pwa.OFF,
) {

    /** A task-name-safe name: `root` for `/`, else the prefix's segments joined by `-`. */
    val name: String get() = if (path == "/") "root" else path.trim('/').replace('/', '-')

    /** Vite's base: the prefix with a trailing slash. */
    val base: String get() = if (path == "/") "/" else "$path/"

    /** Where the files go inside the jar's classpath `static/` directory. */
    val staticDir: String get() = if (path == "/") "static" else "static$path"
}
