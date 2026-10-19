// The types of @jabiz/client/vite (vite.mjs): the preset is plain JavaScript, so that Vite loads it from an
// application's vite.config.ts on any Node version (a linked package is not bundled into the config).
import type { Plugin } from 'vite'
/** The packages an SPA shares with `@jabiz/client` and `@jabiz/ui`: one copy each (two Reacts break hooks). */
export declare const SHARED_PACKAGES: string[]
export declare const TEST_PACKAGES: string[]
export type PwaMode = 'on' | 'off' | 'remove'
/** An icon of the manifest: a PNG file in the SPA's `public/` directory. */
export interface PwaIcon {
  /** Relative to `public/` (`icon-192.png`). */
  src: string
  /** `192x192`. */
  sizes: string
  type?: string
  /** `any` (the default), `maskable`, or both. */
  purpose?: string
}
/** What the web app manifest says of the application; id, start URL and scope are the SPA's prefix. */
export interface PwaOptions {
  name: string
  shortName?: string
  description?: string
  themeColor: string
  backgroundColor: string
  /** At least a 192×192 and a 512×512 icon, and a maskable one. */
  icons: PwaIcon[]
  display?: 'standalone' | 'fullscreen' | 'minimal-ui'
  lang?: string
}
export interface JabizSpaOptions {
  /** The manifest of the PWA; required unless the mode is `off` or `remove`. */
  pwa?: PwaOptions
  /** `VITE_JABIZ_PWA` by default, else `off`. */
  mode?: PwaMode
  /** `VITE_BASE` by default, else `/`. */
  base?: string
  /** `VITE_JABIZ_ENTRY` by default. */
  entry?: string
  /** `VITE_JABIZ_SPA_PREFIXES` (comma-separated) by default. */
  prefixes?: string[]
}
/** The settings of the build, checked. */
export interface SpaSettings {
  base: string
  entry: string | null
  mode: PwaMode
  prefixes: string[]
}
/** The settings from the options and the environment; throws with every problem at once. */
export declare function spaSettings(options?: JabizSpaOptions, env?: Record<string, string | undefined>): SpaSettings
/** The web app manifest of the PWA under `base`. */
export declare function manifestOf(pwa: PwaOptions, base: string): Record<string, unknown>
/** Width and height of a PNG file, or null when it is no PNG. */
export declare function pngSize(bytes: Uint8Array): {
  width: number
  height: number
} | null
/** Every problem of the icons against the files in `publicDir`; empty when they will do. */
export declare function iconProblems(icons: readonly PwaIcon[], publicDir: string): string[]
/** The files to precache: everything the build made or copied but source maps, the manifest and the worker itself. */
export declare function precacheList(files: readonly string[], base: string): string[]
/** The SPA's name in its cache names: `root` for `/`, else the prefix's segments joined by `-` (as `jabizApp`). */
export declare function spaName(base: string): string
/** The worker for its configuration, bundled into one classic script (a Service Worker is not a module here). */
export declare function compileWorker(config: Record<string, unknown>): Promise<string>
/** The preset (see the module's description). */
export declare function jabizSpa(options?: JabizSpaOptions): Plugin[]
