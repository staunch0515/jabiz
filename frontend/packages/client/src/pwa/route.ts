/**
 * What the Service Worker of a jabiz PWA does with a request (decision D43 item 6; docs/design/17-apps-and-branches.md
 * section 4.1), as a pure function so that the rules are tested apart from any browser:
 *
 * - `pass`: not handled at all (no `respondWith`): the browser fetches it as if there were no Service Worker. Every
 *   request that is not a `GET`, goes to another origin, to the API (`/api`), the actuator (`/actuator`), another SPA
 *   of the same jar (`exclude`) or outside this SPA (`base`), and everything not listed below. Nothing of the API is
 *   ever cached, public templates included (they have HTTP caching of their own).
 * - `precache`: a file of the application's shell, cached when the worker was installed; answered from the cache
 *   first.
 * - `navigate`: a page of this SPA: the network first, the cached shell (`index.html`) only when offline. The answer is
 *   never cached, so mailed links and identity provider callbacks, which carry tokens, are never kept.
 */
export type SwRoute = 'pass' | 'precache' | 'navigate'

/** What the worker knows about its SPA: built into it by the `@jabiz/client/vite` preset. */
export interface SwContext {
  /** The origin of the SPA (`self.location.origin`). */
  origin: string
  /** The SPA's prefix with a trailing slash (`/portal/`): its scope. */
  base: string
  /** The prefixes of the other SPAs of the same jar, each with a trailing slash (`/admin/`). */
  exclude: readonly string[]
  /** The paths of the shell's files (`/portal/assets/index-abc.js`). */
  precache: ReadonlySet<string> | readonly string[]
}

/** The parts of a request the decision needs. */
export interface SwRequest {
  url: string
  method: string
  /** `navigate` for a page load (`Request.mode`). */
  mode?: string
}

/** Paths the platform serves itself, whatever SPA lies at `/`: never a Service Worker's business. */
const SERVER_PREFIXES = ['/api', '/actuator']

function under(path: string, prefix: string): boolean {
  // `/admin/` holds `/admin` and `/admin/...`, not `/administrator`.
  const bare = prefix.endsWith('/') ? prefix.slice(0, -1) : prefix
  return prefix === '/' || path === bare || path.startsWith(bare + '/')
}

/** What to do with `request` (see {@link SwRoute}). */
export function swRoute(request: SwRequest, ctx: SwContext): SwRoute {
  if (request.method.toUpperCase() !== 'GET') return 'pass'
  let url: URL
  try {
    url = new URL(request.url)
  } catch {
    return 'pass'
  }
  if (url.origin !== ctx.origin) return 'pass'
  const path = url.pathname
  // As the server: anything starting with /api or /actuator is not a page (decision D43 item 6).
  if (SERVER_PREFIXES.some((prefix) => path.startsWith(prefix))) return 'pass'
  if (!under(path, ctx.base)) return 'pass'
  // Another SPA under this one's scope (an app at / with the administration at /admin/): left alone, longest wins.
  if (ctx.exclude.some((prefix) => prefix.length > ctx.base.length && under(path, prefix))) return 'pass'
  const precached = Array.isArray(ctx.precache)
    ? (ctx.precache as readonly string[]).includes(path)
    : (ctx.precache as ReadonlySet<string>).has(path)
  if (precached && url.search === '') return 'precache'
  if (request.mode === 'navigate') return 'navigate'
  return 'pass'
}

/** The name prefix of every cache of one SPA's shell: `jabiz-shell-<name>-`. */
export function shellCachePrefix(spaName: string): string {
  return `jabiz-shell-${spaName}-`
}

/**
 * The caches to delete when a worker activates: the other versions of its own shell only. The Cache Storage is shared by
 * every SPA of the origin, so another SPA's caches (and the application's own) are left alone: a version is the
 * prefix followed by the build's hash alone, so that `jabiz-shell-portal-` does not take `jabiz-shell-portal-x-…`
 * (the SPA at /portal/x or /portal-x).
 */
export function staleCaches(names: readonly string[], prefix: string, current: string | null): string[] {
  return names.filter((name) => name.startsWith(prefix) && /^[0-9a-f]+$/.test(name.slice(prefix.length))
    && name !== current)
}

/** The SPA's name in its cache names: `root` for `/`, else the prefix's segments joined by `-` (as `jabizApp`). */
export function spaNameOf(base: string): string {
  const trimmed = base.replace(/^\/+|\/+$/g, '')
  return trimmed === '' ? 'root' : trimmed.replace(/\//g, '-')
}
