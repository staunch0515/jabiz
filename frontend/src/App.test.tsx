import type { RouteObject } from 'react-router'
import { describe, expect, it } from 'vitest'
import { appRoutes } from './App'

/** Every route with a path, nested ones included. */
function flatten(routes: RouteObject[]): RouteObject[] {
  return routes.flatMap((route) => [route, ...flatten(route.children ?? [])])
}

/** The paths reached without signing in: signing in itself and the links of mail, whose token is the credential. */
const PUBLIC = ['/login', '/login/oidc', '/mail/unsubscribe', '/auth/verify-email', '/auth/join', '/auth/password-reset',
  '/auth/invitation']

describe('the routes of the admin', () => {
  it('put every page but signing in and the mail links behind signing in, within the shell', () => {
    const outside = appRoutes.filter((route) => route.path).map((route) => route.path)
    expect(outside).toEqual(PUBLIC)
    const guarded = appRoutes.find((route) => !route.path)!
    const shell = guarded.children!.find((route) => route.children?.some((child) => child.index))!
    const pages = flatten(shell.children!).filter((route) => route.path).map((route) => route.path)
    for (const path of ['/data', '/processes', '/tasks', '/reports', '/imports', '/documents', '/audit', '/account/security']) {
      expect(pages, path).toContain(path)
    }
  })
})
