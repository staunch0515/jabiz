import { describe, expect, it } from 'vitest'
import { BellIcon, HistoryIcon, HomeIcon, PersonIcon, ScanIcon, SearchIcon } from './icons'
import { scanTarget, targetPath } from '../scan/target'
import {
  ACCOUNT_BLOCKS_PATH,
  AREA_TABS,
  AREAS,
  areaOf,
  BOTTOM_AREAS,
  DRAWER_MORE,
  DRAWER_ROWS,
  LEGACY_APP_PATHS,
  legacyTarget,
  ME_OWNED,
  ME_SETTINGS,
  MY_PASSES_PATH,
  OLD_ROUTES,
  PRIZES_PATH,
  routeOf,
  SEARCH_PATH,
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

describe('the areas of the taker app (F-F4, Q113 §9.2)', () => {
  it('are four in the bottom menu, scanning and "Me" outside it', () => {
    expect(BOTTOM_AREAS.map((area) => area.key)).toEqual(['home', 'search', 'history', 'messages'])
    expect(AREAS.map((area) => area.key)).toEqual(['home', 'search', 'history', 'messages', 'scan', 'me'])
    expect(SEARCH_PATH).toBe('/search')
  })

  it('each have one icon, the same object wherever the area is named', () => {
    // The prototype's own outlines (`frame/icons.ts`); home and notifications solid when current.
    expect(AREAS.map((area) => area.icon)).toEqual([HomeIcon, SearchIcon, HistoryIcon, BellIcon, ScanIcon, PersonIcon])
    expect(AREAS.filter((area) => area.solidIcon).map((area) => area.key)).toEqual(['home', 'messages'])
    for (const area of AREAS) expect(areaOf(area.key).icon).toBe(area.icon)
    expect(new Set(AREAS.map((area) => area.icon)).size).toBe(AREAS.length)
  })

  it('each lead to a page of their own area', () => {
    for (const area of AREAS) expect(routeOf(area.path)?.area).toBe(area.key)
  })

  it('give the drawer the prototype\'s rows: the taker\'s page, what they have, the settings, then the short ones', () => {
    expect(DRAWER_ROWS.map((row) => row.key)).toEqual(['me', 'kudos', 'prizes', 'certificates', 'maps', 'referrals',
      'settings'])
    expect(DRAWER_MORE.map((row) => row.key)).toEqual(['my-sponsor', 'staff', 'help'])
    for (const row of [...DRAWER_ROWS, ...DRAWER_MORE]) expect(routeOf(row.path), row.path).not.toBeNull()
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

describe('the knowledge maps (F-S3c)', () => {
  it('are pages of "Me", new addresses', () => {
    expect(routeOf('/maps')).toEqual({ page: 'maps', area: 'me', nav: true })
    expect(routeOf('/maps/sample')).toEqual({ page: 'map', area: 'me', nav: true })
    expect(routeOf('/maps/a/b')).toBeNull()
    for (const path of ['/maps', '/maps/sample']) expect(OLD_ROUTES).not.toContain(path)
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

describe('the tabs of the areas (F-F4)', () => {
  it('are the pages of their area, the first being the area\'s own page', () => {
    expect(Object.keys(AREA_TABS)).toEqual(['history', 'scan', 'messages'])
    for (const [key, tabs] of Object.entries(AREA_TABS)) {
      // Q113d-1: "Answered" and "Notifications" have three (the prototype's).
      expect(tabs!.length, key).toBe(key === 'scan' ? 2 : 3)
      expect(tabs![0].path, key).toBe(areaOf(key as AreaKey).path)
      for (const tab of tabs!) expect(routeOf(tab.path)?.area, tab.path).toBe(key)
    }
    expect(AREA_TABS.history!.map((tab) => tab.path)).toEqual(['/history', PRIZES_PATH, '/history/unfinished'])
    expect(AREA_TABS.scan!.map((tab) => tab.path)).toEqual(['/scan', MY_PASSES_PATH])
    expect(AREA_TABS.messages!.map((tab) => tab.path)).toEqual(['/inbox', '/inbox/rewards', '/conversations'])
  })
})

describe('the rows of "Me" (F-F4)', () => {
  const rows = [...ME_OWNED, ...ME_SETTINGS]

  it('have an address that is a page of the app, each key once', () => {
    expect(new Set(rows.map((row) => row.key)).size).toBe(rows.length)
    for (const row of rows) expect(routeOf(row.path), row.path).not.toBeNull()
  })

  it('lead to pages of "Me", except the prizes (a tab of "Answered") and help and the texts (public pages)', () => {
    for (const row of rows) {
      const area = routeOf(row.path)!.area
      if (['help', 'terms', 'privacy', 'contest', 'about'].includes(row.key)) expect(area, row.key).toBeNull()
      else if (row.key === 'prizes') expect(area, row.key).toBe('history')
      else expect(area, row.key).toBe('me')
    }
  })

  it('hold every entry the old account menu and the top-right menu had', () => {
    const paths = rows.map((row) => row.path)
    for (const old of ['/account/profile', '/account/details', '/account/certificates', '/account/security',
      '/account/mail', '/account/preferences', '/account/id', '/referrals', '/wallet', '/maps', '/help', '/about']) {
      expect(paths, old).toContain(old)
    }
    expect(paths).toContain(PRIZES_PATH)
    expect(paths).toContain(ACCOUNT_BLOCKS_PATH)
  })
})
