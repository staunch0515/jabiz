import { describe, expect, it } from 'vitest'
import { SPONSOR } from '../messages'
import { bundle, emptyTexts, keyPaths, LANGUAGES } from '../qb/messageKeys'
import { publicationIdOf } from '../publishing/paths'
import { AREA_KEYS, AREAS, bottomBarAreas, placeOf, rootOf, shownTabs } from './areas'
import defaults from './bottom-bar.default.json'
import { legacyTarget, LEGACY_RULES } from './legacy'
import { shellMessages } from './messages'
import { pageOf } from './routes'

const ALL = { kudos: true, benefits: true }
const NONE = { kudos: false, benefits: false }

describe('the areas (F-F3)', () => {
  it('are the home and eight areas, each with an icon, its tabs unique and every tab a page', () => {
    expect(AREA_KEYS).toEqual(['home', 'quizzes', 'publications', 'prizes', 'messages', 'benefits', 'money', 'assets',
      'hub'])
    for (const area of AREAS) {
      expect(area.icon, area.key).toBeDefined()
      expect(new Set(area.tabs.map((tab) => tab.key)).size, area.key).toBe(area.tabs.length)
      for (const tab of area.tabs) {
        expect(pageOf(tab.path, '', ALL), tab.path).not.toBeNull()
        expect(placeOf(tab.path), tab.path).toEqual({ area, tab })
      }
    }
  })

  it('name every area and tab in every language', () => {
    const english = keyPaths(shellMessages.en)
    for (const lng of LANGUAGES) {
      expect(keyPaths(shellMessages[lng]), lng).toEqual(english)
      expect(keyPaths(bundle(SPONSOR, lng)), lng).toEqual(expect.arrayContaining(english))
      expect(emptyTexts(SPONSOR, lng), lng).toEqual([])
      // Never the retired names of the Sponsor's pages (F-F5b).
      expect(JSON.stringify(shellMessages[lng])).not.toMatch(/后台|管理画面|コンソール|console|back office/i)
    }
    for (const area of AREAS) {
      expect(shellMessages.en.shell.areas[area.key], area.key).toBeTruthy()
      expect(shellMessages.en.shell.bottom[area.key], area.key).toBeTruthy()
      const tabs = shellMessages.en.shell.tabs[area.key] as Record<string, string>
      for (const tab of area.tabs) expect(tabs[tab.key], `${area.key}.${tab.key}`).toBeTruthy()
    }
  })

  it('place a page under the longest tab it is under; nothing outside the areas', () => {
    expect(placeOf('/benefits/offers')?.tab.key).toBe('offers')
    expect(placeOf('/benefits/purchases/p1')?.tab.key).toBe('purchases')
    expect(placeOf('/publications/abc')?.tab.key).toBe('publications')
    expect(placeOf('/publications/map')?.tab.key).toBe('map')
    expect(placeOf('/money/wallet/topups/t1')?.tab.key).toBe('wallet')
    expect(placeOf('/profile/page/stats')?.tab.key).toBe('page')
    expect(placeOf('/account')).toBeNull()
    expect(placeOf('/help')).toBeNull()
  })

  it('hide the Kudos wallet without Kudos and the benefits without a benefit role', () => {
    const money = AREAS.find((area) => area.key === 'money')!
    expect(shownTabs(money, ALL).map((tab) => tab.key)).toEqual(['transactions', 'wallet', 'account', 'ai', 'bills'])
    expect(shownTabs(money, NONE).map((tab) => tab.key)).toEqual(['transactions', 'account', 'ai', 'bills'])
    expect(rootOf(money, NONE)).toBe('/money/transactions')
    expect(AREAS.find((area) => area.key === 'benefits')!.when!(NONE)).toBe(false)
  })

  it('never take a tab of the publications for a publication\'s key', () => {
    expect(publicationIdOf('/publications/map')).toBeNull()
    expect(publicationIdOf('/publications/new')).toBe('new')
    expect(publicationIdOf('/publications/0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b')).toBe(
      '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b')
  })
})

describe('the bottom menu (F-F3)', () => {
  it('starts from the parameter\'s declared value, the home, publications, redeeming and messages', () => {
    expect(bottomBarAreas(defaults.bottomBar)).toEqual(['home', 'publications', 'prizes', 'messages'])
  })

  it('leaves out unknown keys and repeats, in the parameter\'s order', () => {
    expect(bottomBarAreas(' money, home,nothing,money ,assets')).toEqual(['money', 'home', 'assets'])
    expect(bottomBarAreas('')).toEqual([])
  })
})

describe('the old addresses (F-F3)', () => {
  it.each(LEGACY_RULES.map((rule) => [rule.example, rule.expect]))('lead %s to %s', (example, expected) => {
    const url = new URL(example, 'https://quizbuks.test')
    expect(legacyTarget(url.pathname, url.search)).toBe(expected)
  })

  it('lead to pages that exist, never to another old address, and never take an address in use', () => {
    for (const rule of LEGACY_RULES) {
      const url = new URL(rule.expect, 'https://quizbuks.test')
      expect(legacyTarget(url.pathname, url.search), rule.expect).toBeNull()
      expect(pageOf(url.pathname, url.search, ALL), rule.expect).not.toBeNull()
      // Only the library's ?tab= forms share an address with a page of today.
      if (rule.tab === undefined || rule.from !== '/assets') {
        expect(pageOf(rule.from.replace(/\/$/, ''), '', ALL), rule.from).toBeNull()
      }
    }
  })

  it('leave the addresses of today alone', () => {
    for (const path of ['/home', '/quizzes', '/publications', '/assets', '/profile', '/money/wallet', '/account']) {
      expect(legacyTarget(path, ''), path).toBeNull()
    }
    expect(legacyTarget('/assets', '?tab=unknown')).toBeNull()
  })
})
