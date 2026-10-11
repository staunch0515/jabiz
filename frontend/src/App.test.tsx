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
  it('mark the pages built with @jabiz/ui, which follow the appearance, and only those', () => {
    // Phases 15b-1 and 15b-2: the catalogs, the process form, the history and the list (with its form sheet).
    for (const path of ['/data', '/data/:datasetId', '/data/:datasetId/:entityId/history', '/processes', '/processes/:name/:version']) {
      expect(marked(path), path).toBe(true)
    }
    // Still on Ant Design (phase 15c): kept in the light tokens.
    for (const path of ['/tasks', '/reports/run', '/imports/run', '/audit', '/account/security']) {
      expect(marked(path), path).toBe(false)
    }
  })
})
