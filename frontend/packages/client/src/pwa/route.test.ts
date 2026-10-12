import { describe, expect, it } from 'vitest'
import { shellCachePrefix, spaNameOf, staleCaches, swRoute, type SwContext } from './route'

const ORIGIN = 'https://shop.example.com'

/** A PWA at /portal/ in a jar with the administration at / and a sponsor SPA at /sponsor/. */
const PORTAL: SwContext = {
  origin: ORIGIN,
  base: '/portal/',
  exclude: ['/', '/sponsor/'],
  precache: new Set(['/portal/index.html', '/portal/assets/index-abc.js', '/portal/assets/index-def.css',
    '/portal/icon-192.png']),
}

/** An app at / (QuizBuks' form): its scope covers the other SPAs, which it must leave alone. */
const ROOT: SwContext = {
  origin: ORIGIN,
  base: '/',
  exclude: ['/admin/', '/sponsor/'],
  precache: ['/index.html', '/assets/index-abc.js'],
}

describe('swRoute', () => {
  it.each([
    // The API, the actuator and public templates: never intercepted, never cached.
    ['GET', '/api/auth/me', undefined, 'pass'],
    ['GET', '/api/public/queries/news?limit=5', undefined, 'pass'],
    ['GET', '/api', 'navigate', 'pass'],
    ['GET', '/api/files/1/content', 'navigate', 'pass'],
    ['GET', '/actuator/health', undefined, 'pass'],
    // Not GET.
    ['POST', '/portal/assets/index-abc.js', undefined, 'pass'],
    ['HEAD', '/portal/index.html', undefined, 'pass'],
    ['POST', '/portal/orders', 'navigate', 'pass'],
    // Other SPAs and outside the scope.
    ['GET', '/', 'navigate', 'pass'],
    ['GET', '/data/carrier', 'navigate', 'pass'],
    ['GET', '/sponsor/', 'navigate', 'pass'],
    ['GET', '/portalx/', 'navigate', 'pass'],
    // The shell: cache first, by its exact address.
    ['GET', '/portal/assets/index-abc.js', undefined, 'precache'],
    ['GET', '/portal/index.html', undefined, 'precache'],
    ['GET', '/portal/icon-192.png', undefined, 'precache'],
    ['GET', '/portal/assets/index-abc.js?v=2', undefined, 'pass'],
    ['GET', '/portal/assets/other.js', undefined, 'pass'],
    // Pages: network first, the shell when offline; with a token too, and never cached.
    ['GET', '/portal/', 'navigate', 'navigate'],
    ['GET', '/portal', 'navigate', 'navigate'],
    ['GET', '/portal/orders/42', 'navigate', 'navigate'],
    ['GET', '/portal/auth/verify-email?entry=portal&token=secret', 'navigate', 'navigate'],
    ['GET', '/portal/login/oidc?state=s&code=c', 'navigate', 'navigate'],
    // Anything else of the SPA that is no page: left to the browser.
    ['GET', '/portal/orders', 'cors', 'pass'],
  ])('%s %s (%s) is %s', (method, path, mode, expected) => {
    expect(swRoute({ url: ORIGIN + path, method, mode }, PORTAL)).toBe(expected)
  })

  it('leaves other origins alone, even with a path of the shell', () => {
    expect(swRoute({ url: 'https://cdn.example.com/portal/assets/index-abc.js', method: 'GET' }, PORTAL)).toBe('pass')
    expect(swRoute({ url: 'https://idp.example.com/authorize', method: 'GET', mode: 'navigate' }, PORTAL)).toBe('pass')
    expect(swRoute({ url: 'not a url', method: 'GET', mode: 'navigate' }, PORTAL)).toBe('pass')
  })

  it('an app at / leaves the SPAs under its scope and the API alone', () => {
    expect(swRoute({ url: `${ORIGIN}/admin/`, method: 'GET', mode: 'navigate' }, ROOT)).toBe('pass')
    expect(swRoute({ url: `${ORIGIN}/admin`, method: 'GET', mode: 'navigate' }, ROOT)).toBe('pass')
    expect(swRoute({ url: `${ORIGIN}/admin/assets/a.js`, method: 'GET' }, ROOT)).toBe('pass')
    expect(swRoute({ url: `${ORIGIN}/sponsor/campaigns`, method: 'GET', mode: 'navigate' }, ROOT)).toBe('pass')
    expect(swRoute({ url: `${ORIGIN}/api/auth/me`, method: 'GET' }, ROOT)).toBe('pass')
    expect(swRoute({ url: `${ORIGIN}/administrator`, method: 'GET', mode: 'navigate' }, ROOT)).toBe('navigate')
    expect(swRoute({ url: `${ORIGIN}/quiz/7`, method: 'GET', mode: 'navigate' }, ROOT)).toBe('navigate')
    expect(swRoute({ url: `${ORIGIN}/assets/index-abc.js`, method: 'GET' }, ROOT)).toBe('precache')
  })
})

describe('the caches of a shell', () => {
  it('are named after the SPA, and only its own other versions are stale', () => {
    expect(spaNameOf('/')).toBe('root')
    expect(spaNameOf('/portal/')).toBe('portal')
    expect(shellCachePrefix('portal')).toBe('jabiz-shell-portal-')
    const names = ['jabiz-shell-portal-aaa', 'jabiz-shell-portal-bbb', 'jabiz-shell-portalx-ccc',
      'jabiz-shell-root-ddd', 'app-images', 'jabiz-shell-portal', 'jabiz-shell-portal-x-abc123', 'jabiz-shell-portal-']
    // The version before the current one stays (in creation order, as caches.keys() gives them): tabs still running
    // it may load its lazy chunks.
    expect(staleCaches(names, 'jabiz-shell-portal-', 'jabiz-shell-portal-bbb')).toEqual([])
    expect(staleCaches(['jabiz-shell-portal-a1', 'jabiz-shell-portal-b2', 'jabiz-shell-portal-c3'], 'jabiz-shell-portal-',
      'jabiz-shell-portal-c3')).toEqual(['jabiz-shell-portal-a1'])
    expect(staleCaches(['jabiz-shell-portal-a1', 'jabiz-shell-portal-c3'], 'jabiz-shell-portal-',
      'jabiz-shell-portal-c3')).toEqual([])
    // Not the SPA at /portal/x (or /portal-x), whose caches also start with jabiz-shell-portal-.
    expect(staleCaches(names, 'jabiz-shell-portal-', null)).toEqual(['jabiz-shell-portal-aaa',
      'jabiz-shell-portal-bbb'])
  })
})
