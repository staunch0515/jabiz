import { describe, expect, it } from 'vitest'
import { History, House, MessagesSquare, ScanLine, UserRound } from 'lucide-react'
import { scanTarget, targetPath } from '../scan/target'
import {
  ACCOUNT_BLOCKS_PATH,
  AREAS,
  areaOf,
  LEGACY_APP_PATHS,
  legacyTarget,
  MY_PASSES_PATH,
  OLD_ROUTES,
  PRIZES_PATH,
  routeOf,
  type AreaKey,
} from './areas'

/**
 * The area each old address belongs to (F-F4 section 2.2), written out so that the table is pinned: null for the pages
 * outside the areas (the entry pages, the public ones, the welcome step).
 */
const EXPECTED_AREAS: Readonly<Record<string, AreaKey | null>> = {
  '/': 'home', '/?q=sample': 'home', '/?shorts=sample': 'home', '/p/sample': 'home',
  '/history': 'history', '/play/sample': 'history', '/result/sample': 'history',
  '/scan': 'scan', '/redeem/sample': 'scan', '/pass/sample': 'scan',
  '/inbox': 'messages', '/inbox/message/sample': 'messages', '/inbox/broadcast/sample': 'messages',
  '/conversations': 'messages', '/conversations/sample': 'messages',
  '/account': 'me', '/account?section=details': 'me', '/account/profile': 'me', '/account/details': 'me',
  '/account/certificates': 'me', '/account/security': 'me', '/account/mail': 'me', '/account/preferences': 'me',
  '/account/id': 'me', '/account/delete': 'me',
  '/wallet': 'me', '/wallet/balance': 'me', '/wallet/rewards': 'me', '/wallet/history': 'me',
  '/wallet/kudos/PLATFORM': 'me', '/wallet/kudos/SPONSOR_CASH': 'me', '/wallet/kudos/OFFLINE_REDEEM': 'me',
  '/wallet/kudos/DIGITAL_PRIZE': 'me', '/wallet/payout': 'me', '/wallet/bank': 'me',
  '/referrals': 'me', '/referral/sample': 'me', '/my-sponsor': 'me', '/staff': 'me', '/staff/handled': 'me',
  '/welcome': null, '/help': null, '/about': null,
  '/legal/terms': null, '/legal/sponsor-terms': null, '/legal/privacy': null, '/legal/commerce': null,
  '/legal/ai': null, '/legal/contest-rules': null,
  '/verify/sample': null, '/u/sample': null, '/s/sample': null,
  '/login': null, '/signup': null, '/password-reset': null, '/login/oidc': null, '/mail/unsubscribe': null,
  '/delete-account': null,
  '/auth/verify-email': null, '/auth/join': null, '/auth/password-reset': null, '/auth/email-link': null,
  '/auth/account-deletion': null, '/auth/invitation': null, '/auth/passkey-confirm': null,
}

/** An old address as its path and query. */
function split(address: string): [string, string] {
  const at = address.indexOf('?')
  return at < 0 ? [address, ''] : [address.slice(0, at), address.slice(at)]
}

describe('the areas of the taker app (F-F4)', () => {
  it('are five, scanning in the middle and the only one raised', () => {
    expect(AREAS.map((area) => area.key)).toEqual(['home', 'history', 'scan', 'messages', 'me'])
    expect(AREAS[Math.floor(AREAS.length / 2)].key).toBe('scan')
    expect(AREAS.filter((area) => area.raised).map((area) => area.key)).toEqual(['scan'])
  })

  it('each have one icon, the same object wherever the area is named', () => {
    expect(AREAS.map((area) => area.icon)).toEqual([House, History, ScanLine, MessagesSquare, UserRound])
    for (const area of AREAS) expect(areaOf(area.key).icon).toBe(area.icon)
    expect(new Set(AREAS.map((area) => area.icon)).size).toBe(AREAS.length)
  })

  it('each lead to a page of their own area', () => {
    for (const area of AREAS) expect(routeOf(area.path)?.area).toBe(area.key)
  })
})

describe('the old addresses (F-F4 section 3)', () => {
  it('are pinned: every one has its expected area, and the list covers them all', () => {
    expect([...OLD_ROUTES].sort()).toEqual(Object.keys(EXPECTED_AREAS).sort())
    expect(new Set(OLD_ROUTES).size).toBe(OLD_ROUTES.length)
  })

  it.each(OLD_ROUTES.map((address) => [address]))('%s is still a page of the app', (address) => {
    const [path, search] = split(address)
    const found = routeOf(path, search)
    expect(found, `${address} has no page: add it to LEGACY_APP_PATHS`).not.toBeNull()
    expect(found!.area).toBe(EXPECTED_AREAS[address])
  })

  it('show the frame\'s navigation in the areas, except while answering', () => {
    for (const address of OLD_ROUTES) {
      const [path, search] = split(address)
      const found = routeOf(path, search)!
      expect(found.nav, address).toBe(found.area !== null && found.page !== 'play')
    }
  })

  it('that changed lead where the one list says, each to a page there is', () => {
    expect(LEGACY_APP_PATHS.length).toBeGreaterThan(0)
    for (const legacy of LEGACY_APP_PATHS) {
      const [path, search] = split(legacy.from)
      expect(legacyTarget(path, search), legacy.from).toBe(legacy.to)
      const [to, query] = split(legacy.to)
      expect(routeOf(to, query), legacy.to).not.toBeNull()
      expect(legacyTarget(to, query), `${legacy.to} leads on again`).toBeNull()
      expect(legacy.reason.trim(), legacy.from).not.toBe('')
    }
  })

  it('that changed are found by path and query, nothing else', () => {
    expect(legacyTarget('/delete-account')).toBe('/auth/account-deletion')
    expect(legacyTarget('/account', '?section=details')).toBe('/account/details')
    expect(legacyTarget('/account', '?section=details&from=mail')).toBe('/account/details')
    expect(legacyTarget('/account')).toBeNull()
    expect(legacyTarget('/account', '?section=security')).toBeNull()
    expect(legacyTarget('/account/details')).toBeNull()
    expect(legacyTarget('/delete-account/')).toBeNull()
  })

  it('are no page when the app has none', () => {
    for (const path of ['/nothing', '/account/nothing', '/wallet/nothing', '/wallet/kudos/NOTHING', '/auth/nothing',
      '/legal/nothing', '/p/', '/p/a/b', '/history/nothing']) {
      expect(routeOf(path), path).toBeNull()
    }
  })
})

describe('the pages F-F4b adds', () => {
  it('have their areas recorded already, and none of them was an old address', () => {
    expect(routeOf(PRIZES_PATH)).toEqual({ page: 'prizes', area: 'history', nav: true })
    expect(routeOf(MY_PASSES_PATH)).toEqual({ page: 'myPasses', area: 'scan', nav: true })
    expect(routeOf(ACCOUNT_BLOCKS_PATH)).toEqual({ page: 'accountBlocks', area: 'me', nav: true })
    for (const path of [PRIZES_PATH, MY_PASSES_PATH, ACCOUNT_BLOCKS_PATH]) expect(OLD_ROUTES).not.toContain(path)
  })
})

describe('a scanned code (T12 unchanged)', () => {
  it('opens a page of the home or of scanning', () => {
    const origin = 'https://quizbuks.example'
    for (const [page, area] of [['p', 'home'], ['redeem', 'scan'], ['pass', 'scan']] as const) {
      const target = scanTarget(`${origin}/taker/${page}/abc`, origin, '/taker/')
      expect(target).toEqual({ page, id: 'abc' })
      expect(routeOf(targetPath(target!))?.area).toBe(area)
    }
  })
})
