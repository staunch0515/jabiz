import type { RouteObject } from 'react-router'
import { describe, expect, it } from 'vitest'
import { appRoutes } from './App'

/** Every route with a path, nested ones included. */
function flatten(routes: RouteObject[]): RouteObject[] {
  return routes.flatMap((route) => [route, ...flatten(route.children ?? [])])
}

const marked = (path: string) =>
  (flatten(appRoutes).find((route) => route.path === path)?.handle as { ui?: string } | undefined)?.ui === 'jabiz'

describe('the routes of the admin', () => {
  it('mark the pages built with @jabiz/ui, which follow the appearance', () => {
    // Phases 15b-1 and 15b-2: the catalogs, the process form, the history and the list (with its form sheet).
    for (const path of ['/data', '/data/:datasetId', '/data/:datasetId/:entityId/history', '/processes', '/processes/:name/:version']) {
      expect(marked(path), path).toBe(true)
    }
    // Phase 15c-1: the reports and the imports.
    for (const path of ['/reports', '/reports/run', '/reports/archive', '/imports', '/imports/run', '/imports/runs']) {
      expect(marked(path), path).toBe(true)
    }
    // Phase 15c-2: the rest; with 16g's sessions page every page of the admin is built with @jabiz/ui.
    for (const path of ['/tasks', '/documents', '/audit', '/access-review', '/integrity', '/retention', '/account/security', '/security/sessions']) {
      expect(marked(path), path).toBe(true)
    }
    // No page within the shell is left pinned to the light tokens.
    const shell = flatten(appRoutes).find((route) => route.children?.some((child) => child.index))!
    for (const route of shell.children!.filter((child) => child.path && child.path !== '*')) {
      expect(marked(route.path!), route.path).toBe(true)
    }
  })
})
