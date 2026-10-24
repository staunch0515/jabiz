import { describe, expect, it } from 'vitest'
import {
  actionsOf,
  asSurvey,
  formOf,
  inputOf,
  nextTier,
  problemPlace,
  timeNote,
  toPublication,
  type PublicationForm,
} from './api'
import { publicationIdOf, publicationPath } from './paths'

const FORM: PublicationForm = {
  name: '  Spring quiz ',
  quizId: 'q1',
  versionId: 'v1',
  regions: ['ASIA', 'GLOBAL', 'JP'],
  startAt: '2026-11-01T00:00:00.000Z',
  endAt: '2026-11-30T00:00:00.000Z',
  timeLimitSec: '',
  passScore: '2',
  budgetCap: '1000',
  maxWinners: ' 10 ',
  tiers: [
    { key: 'a', tierId: 't1', fromScore: '0', toScore: '1', reward: '0' },
    { key: 'b', tierId: null, fromScore: '2', toScore: '2', reward: '100' },
  ],
  rewardMethod: 'PLATFORM',
  redeemDescription: '',
}

describe('the publishing paths', () => {
  it('know a publication\'s page and the new one', () => {
    expect(publicationIdOf('/publications/new')).toBe('new')
    expect(publicationIdOf('/publications/p%201')).toBe('p 1')
    expect(publicationIdOf('/publications')).toBeNull()
    expect(publicationIdOf('/publications/p1/x')).toBeNull()
    expect(publicationPath('p 1')).toBe('/publications/p%201')
  })
})

describe('the wizard\'s form', () => {
  it('sends the whole draft: regions in the server\'s order, blank numbers as none, tiers with their keys', () => {
    expect(inputOf(FORM, 'p1', 4)).toEqual({ input: {
      publicationId: 'p1', versionId: 'v1', name: 'Spring quiz', regions: ['GLOBAL', 'JP', 'ASIA'],
      startAt: '2026-11-01T00:00:00.000Z', endAt: '2026-11-30T00:00:00.000Z', timeLimitSec: null, passScore: 2,
      budgetCap: 1000, maxWinners: 10, baseRevision: 4, tiers: [
        { tierId: 't1', fromScore: 0, toScore: 1, reward: 0 },
        { tierId: null, fromScore: 2, toScore: 2, reward: 100 },
      ], rewardMethod: 'PLATFORM', redeemDescription: null } })
  })

  it('marks what is not a whole number, and a tier\'s missing values, without sending', () => {
    const form = { ...FORM, passScore: '1.5', maxWinners: '-1',
      tiers: [{ key: 'a', tierId: null, fromScore: '0', toScore: '', reward: 'x' }] }
    expect(inputOf(form, null, null)).toEqual({ errors: { passScore: 'wholeNumber', maxWinners: 'wholeNumber',
      'tiers[0].toScore': 'required', 'tiers[0].reward': 'wholeNumber' } })
  })

  it('starts from the publication as read', () => {
    const form = formOf({
      publication: toPublication({ publicationId: 'p1', name: 'N', regions: 'GLOBAL,US', status: 'DRAFT',
        timeLimitSec: '300', budgetCap: '500', revision: '3', editedAt: '2026-10-14T00:00:00Z' }),
      tiers: [{ tierId: 't1', fromScore: 0, toScore: 3, reward: '50' }],
    })
    expect(form).toMatchObject({ name: 'N', regions: ['GLOBAL', 'US'], timeLimitSec: '300', budgetCap: '500',
      passScore: '', maxWinners: '' })
    expect(form.tiers).toMatchObject([{ tierId: 't1', fromScore: '0', toScore: '3', reward: '50' }])
    expect(formOf(null)).toMatchObject({ name: '', regions: [], tiers: [] })
  })

  it('adds a tier after the last, up to the full score', () => {
    expect(nextTier([], 5)).toMatchObject({ tierId: null, fromScore: '0', toScore: '5', reward: '' })
    expect(nextTier(FORM.tiers, 4)).toMatchObject({ fromScore: '3', toScore: '4' })
    expect(nextTier(FORM.tiers, null)).toMatchObject({ fromScore: '3', toScore: '' })
  })
})

describe('what the pages say about a publication', () => {
  it('names the part of the form a problem is about', () => {
    expect(problemPlace('passScore')).toBe('passScore')
    expect(problemPlace('tiers[2].fromScore')).toBe('tiers')
    expect(problemPlace('tiers')).toBe('tiers')
    expect(problemPlace('version.questions[0].options')).toBe('version')
    expect(problemPlace(null)).toBeNull()
    expect(problemPlace('baseRevision')).toBeNull()
  })

  it('offers what each state allows', () => {
    expect(actionsOf('DRAFT')).toMatchObject({ edit: true, submit: true, delete: true, withdraw: false, pause: false })
    expect(actionsOf('PENDING_REVIEW')).toMatchObject({ withdraw: true, pause: false, delete: false })
    expect(actionsOf('LIVE')).toMatchObject({ pause: true, resume: false, copy: true })
    expect(actionsOf('FUNDS_SHORT')).toMatchObject({ pause: true })
    expect(actionsOf('PAUSED')).toMatchObject({ pause: false, resume: true })
    expect(actionsOf('PAUSED', 'SPONSOR')).toMatchObject({ resume: true })
    // Q10b: what an administrator paused only an administrator resumes.
    expect(actionsOf('PAUSED', 'ADMIN')).toMatchObject({ pause: false, resume: false, copy: false })
    expect(actionsOf('ENDED', null, 'ADMIN')).toMatchObject({ copy: false })
    expect(actionsOf('ENDED', null, 'EXPIRED')).toMatchObject({ copy: true })
    expect(actionsOf('ENDED', 'ADMIN', 'EXPIRED')).toMatchObject({ copy: false })
    expect(actionsOf('ENDED', 'SPONSOR', 'EXPIRED')).toMatchObject({ copy: true })
    expect(actionsOf('ENDED')).toMatchObject({ pause: false, resume: false, withdraw: false, copy: true })
  })

  it('tells what the times say beyond the state', () => {
    const start = '2026-10-14T00:00:00Z'
    const end = '2026-10-15T00:00:00Z'
    const at = (iso: string) => Date.parse(iso)
    expect(timeNote('SCHEDULED', start, end, at('2026-10-13T23:59:59Z'))).toBeNull()
    expect(timeNote('SCHEDULED', start, end, at(start))).toBe('starting')
    expect(timeNote('LIVE', start, end, at('2026-10-14T12:00:00Z'))).toBeNull()
    expect(timeNote('LIVE', start, end, at(end))).toBe('ending')
    expect(timeNote('PAUSED', start, end, at(end))).toBe('ending')
    expect(timeNote('ENDED', start, end, at(end))).toBeNull()
    expect(timeNote('DRAFT', start, end, at(end))).toBeNull()
  })
})

describe('a survey\'s form (Q12)', () => {
  it('saves a draft before its reward is typed: no tiers yet, nothing typed wrong', () => {
    const shaped = asSurvey({ ...FORM, passScore: '', tiers: [] }, 5, '')
    expect(shaped.passScore).toBe('5')
    expect(shaped.tiers).toEqual([])
    expect(inputOf(shaped, null, null).errors).toBeUndefined()
  })

  it('builds the two tiers once the reward is typed', () => {
    const shaped = asSurvey({ ...FORM, tiers: [] }, 5, '30')
    expect(shaped.tiers.map((tier) => [tier.fromScore, tier.toScore, tier.reward])).toEqual([
      ['0', '4', '0'], ['5', '5', '30']])
  })
})
