/**
 * The URL prefix the admin frontend is served under (docs/design/16-content-authoring.md section 6): `/` by default,
 * or a sub-path such as `/admin/` when an application puts its public website at the root. Built with `VITE_BASE`;
 * API paths stay absolute (`/api`).
 */

const BASE = /^\/(?:[a-z0-9][a-z0-9-]*\/)*$/

/** The Vite `base` for a `VITE_BASE` value: `/` when unset; otherwise it must look like `/admin/`. */
export function viteBase(value: string | undefined): string {
  if (value === undefined || value === '') return '/'
  if (!BASE.test(value)) throw new Error(`VITE_BASE must be "/" or like "/admin/", was "${value}"`)
  return value
}

/** React Router's `basename` for Vite's `BASE_URL`: the prefix without its trailing slash (`/` stays `/`). */
export function routerBasename(baseUrl: string): string {
  return baseUrl === '/' ? '/' : baseUrl.replace(/\/+$/, '')
}
