package com.jabiz.gradle

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
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

    private val paths = mutableSetOf<String>()

    /**
     * Packages the single-page application built from [sourceDir] (a pnpm project, relative to this module) under
     * the URL prefix [path]: `/` or a prefix such as `/admin` (leading slash, no trailing slash). [extension] names
     * the directory (relative to this module) of the application's own admin pages, compiled into the admin
     * frontend (docs/design/12-frontend.md section 9, decision D22).
     */
    fun spa(path: String, sourceDir: String, extension: String? = null) {
        require(PATH.matches(path)) { "SPA path must be \"/\" or like \"/admin\", was \"$path\"" }
        require(paths.add(path)) { "SPA path \"$path\" is declared twice" }
        require(extension == null || extension.isNotBlank()) { "SPA extension directory must not be blank" }
        register(SpaSpec(path, sourceDir, extension))
    }

    private companion object {
        val PATH = Regex("/|(/[a-z0-9][a-z0-9-]*)+")
    }
}

/**
 * One SPA of an application: its URL prefix, the pnpm project it is built from and, for the admin frontend, the
 * directory of the application's own pages.
 */
data class SpaSpec(val path: String, val sourceDir: String, val extension: String? = null) {

    /** A task-name-safe name: `root` for `/`, else the prefix's segments joined by `-`. */
    val name: String get() = if (path == "/") "root" else path.trim('/').replace('/', '-')

    /** Vite's base: the prefix with a trailing slash. */
    val base: String get() = if (path == "/") "/" else "$path/"

    /** Where the files go inside the jar's classpath `static/` directory. */
    val staticDir: String get() = if (path == "/") "static" else "static$path"
}
