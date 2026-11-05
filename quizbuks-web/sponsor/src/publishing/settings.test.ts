import { describe, expect, it } from 'vitest'
import { inputOf, type PublicationDetail } from './api'
import {
  durationDays,
  endAfter,
  formOfSettings,
  nextHour,
  parseSettings,
  rescaleTiers,
  SETTINGS_FORMAT,
  SETTINGS_VERSION,
  settingsOf,
  type PublicationSettings,
} from './settings'

const DETAIL = {
  publication: {
    publicationId: 'p1', name: 'Onboarding', cover: 'f-cover', covers: {}, quizId: 'q1', versionId: 'v1',
    versionLabel: 'v1', regions: ['CN'], startAt: '2026-11-01T00:00:00Z', endAt: '2026-11-15T00:00:00Z',
    timeLimitSec: 600, passScore: 8, pickCount: null, entryFee: 0, budgetCap: '5000', maxWinners: 50,
    rewardMethod: 'OFFLINE_REDEEM', redeemDescription: 'At the front desk', benefitPurchaseId: 'b1',
    digitalDelivery: null, digitalDescription: null, digitalSendDays: null, audience: 'PUBLIC', referral: null,
    languages: ['zh', 'en'], translations: { en: { name: 'Onboarding', redeemDescription: 'At reception' } },
    fullScore: 10, status: 'LIVE', revision: 4,
  },
  tiers: [
    { tierId: 't1', fromScore: 0, toScore: 7, reward: '0' },
    { tierId: 't2', fromScore: 8, toScore: 10, reward: '100' },
  ],
} as unknown as PublicationDetail

const PLACE = { quizId: 'q2', versionId: 'v9', fullScore: null, startAt: '2026-12-01T00:00:00Z',
  endAt: '2026-12-15T00:00:00Z', name: 'Onboarding' }

describe('a publication\'s settings file (Q102)', () => {
  it('keeps what is worth reusing and leaves out what belongs to one run or one account', () => {
    const settings = settingsOf(DETAIL, [{ key: 'gender', options: ['F'] }])
    expect(settings).toMatchObject({ format: SETTINGS_FORMAT, version: SETTINGS_VERSION, name: 'Onboarding',
      fullScore: 10, durationDays: 14, passScore: 8, budgetCap: 5000, maxWinners: 50, rewardMethod: 'OFFLINE_REDEEM',
      redeemDescription: 'At the front desk', regions: ['CN'], languages: ['zh', 'en'],
      conditions: [{ key: 'gender', options: ['F'] }] })
    expect(settings.tiers).toEqual([{ fromScore: 0, toScore: 7, reward: 0 }, { fromScore: 8, toScore: 10, reward: 100 }])
    const written = JSON.stringify(settings)
    for (const left of ['versionId', 'quizId', 'startAt', 'cover', 'benefitPurchaseId', 'digitalContent', 'f-cover']) {
      expect(written).not.toContain(left)
    }
  })

  it('reads back what it wrote', () => {
    const settings = settingsOf(DETAIL, [{ key: 'age', min: 20, max: 40 }])
    const read = parseSettings(JSON.parse(JSON.stringify(settings)))
    expect(read).toEqual({ settings })
  })

  it('refuses another file and a newer format, and drops values of the wrong kind', () => {
    expect(parseSettings({ format: 'quizbuks.quiz', version: 1 })).toEqual({ problem: 'notSettings' })
    expect(parseSettings([])).toEqual({ problem: 'notSettings' })
    expect(parseSettings({ format: SETTINGS_FORMAT, version: SETTINGS_VERSION + 1 })).toEqual({ problem: 'newerVersion' })
    const read = parseSettings({ format: SETTINGS_FORMAT, version: 1, name: 'X', passScore: -1, rewardMethod: 'GOLD',
      regions: ['JP', 'MARS'], tiers: [{ fromScore: 0, toScore: 'x', reward: 1 }], audience: 'INVITED',
      conditions: [{ key: 'gender', options: ['F'] }] })
    expect('settings' in read && read.settings).toMatchObject({ passScore: null, rewardMethod: 'PLATFORM', regions: [],
      tiers: [], audience: 'INVITED', conditions: [] })
  })
})

describe('rescaling to another full score', () => {
  it('keeps every score covered once, the last tier ending at the full score', () => {
    expect(rescaleTiers([{ fromScore: 0, toScore: 7 }, { fromScore: 8, toScore: 10 }], 10, 20))
      .toEqual([{ fromScore: 0, toScore: 15 }, { fromScore: 16, toScore: 20 }])
    expect(rescaleTiers([{ fromScore: 0, toScore: 49 }, { fromScore: 50, toScore: 79 }, { fromScore: 80, toScore: 100 }],
      100, 5)).toEqual([{ fromScore: 0, toScore: 2 }, { fromScore: 3, toScore: 3 }, { fromScore: 4, toScore: 5 }])
  })

  it('drops a tier that shrinks to nothing, and changes nothing on the same or an unknown full score', () => {
    expect(rescaleTiers([{ fromScore: 0, toScore: 0 }, { fromScore: 1, toScore: 99 }, { fromScore: 100, toScore: 100 }],
      100, 2)).toEqual([{ fromScore: 0, toScore: 1 }, { fromScore: 2, toScore: 2 }])
    const tiers = [{ fromScore: 0, toScore: 10 }]
    expect(rescaleTiers(tiers, 10, 10)).toEqual(tiers)
    expect(rescaleTiers(tiers, null, 10)).toEqual(tiers)
  })

  it('makes a survey\'s one reward at its new full score', () => {
    expect(rescaleTiers([{ fromScore: 0, toScore: 4 }, { fromScore: 5, toScore: 5 }], 5, 8))
      .toEqual([{ fromScore: 0, toScore: 7 }, { fromScore: 8, toScore: 8 }])
  })
})

describe('a draft from settings', () => {
  const settings = settingsOf(DETAIL, []) as PublicationSettings

  it('is saved as one typed by hand, on the version and times chosen', () => {
    const built = inputOf(formOfSettings(settings, PLACE), null, null)
    expect(built.input).toMatchObject({ publicationId: null, versionId: 'v9', name: 'Onboarding',
      startAt: PLACE.startAt, endAt: PLACE.endAt, passScore: 8, budgetCap: 5000, maxWinners: 50, regions: ['CN'],
      rewardMethod: 'OFFLINE_REDEEM', redeemDescription: 'At the front desk', cover: null, languages: ['zh', 'en'],
      translations: { en: { name: 'Onboarding', redeemDescription: 'At reception' } } })
    expect(built.input?.tiers).toEqual([{ tierId: null, fromScore: 0, toScore: 7, reward: 0 },
      { tierId: null, fromScore: 8, toScore: 10, reward: 100 }])
    expect(built.input).not.toHaveProperty('benefitPurchaseId')
  })

  it('rescales onto another full score, and keeps no translated name once renamed', () => {
    const built = inputOf(formOfSettings(settings, { ...PLACE, fullScore: 20, name: 'Onboarding 2027' }), null, null)
    expect(built.input).toMatchObject({ passScore: 16, name: 'Onboarding 2027',
      translations: { en: { redeemDescription: 'At reception' } } })
    expect(built.input?.translations?.en).not.toHaveProperty('name')
    expect(built.input?.tiers.map((t) => [t.fromScore, t.toScore])).toEqual([[0, 15], [16, 20]])
  })
})

describe('the times of a copy or an import', () => {
  it('starts at the next whole hour and ends the days after', () => {
    expect(nextHour(new Date('2026-11-05T04:12:30Z'))).toBe('2026-11-05T05:00:00.000Z')
    expect(endAfter('2026-11-05T05:00:00.000Z', 14)).toBe('2026-11-19T05:00:00.000Z')
    expect(durationDays('2026-11-01T00:00:00Z', '2026-11-08T01:00:00Z')).toBe(8)
    expect(durationDays(null, '2026-11-08T00:00:00Z')).toBeNull()
  })
})
