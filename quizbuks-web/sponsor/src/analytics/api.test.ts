import { describe, expect, it } from 'vitest'
import {
  analyticsIdOf,
  analyticsPath,
  bucketLabel,
  funnelOf,
  percent,
  safeZone,
  toDashboard,
  toOverview,
  toQuestion,
  toTrendTotals,
  toTrendPoint,
  trendParams,
  zoneTime,
} from './api'

const P1 = '0190a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b'

describe('the analytics calls', () => {
  it('ask for the last 24 hours by the hour and the last 7 days by the day, the bucket of now last', () => {
    // 10:00 UTC on the hour: the 24th bucket is the hour of now, the first 23 hours before.
    expect(trendParams('HOURS_24', '2026-10-18T10:00:00.000Z', P1)).toEqual({
      bucket: 'HOUR', from: '2026-10-17T11:00:00.000Z', to: '2026-10-18T10:01:00.000Z', publicationId: P1,
    })
    expect(trendParams('DAYS_7', '2026-10-18T10:37:00.000Z')).toEqual({
      bucket: 'DAY', from: '2026-10-12T10:37:00.000Z', to: '2026-10-18T10:38:00.000Z',
    })
  })

  it('label the buckets in the deployment\'s zone, never the browser\'s', () => {
    // 15:00 UTC is midnight in Tokyo: the hour is 00:00 and the day the server's date.
    const point = toTrendPoint({ bucketStart: '2026-10-17T15:00:00Z', day: '2026-10-18', completions: '3' })
    expect(bucketLabel(point, 'HOURS_24', 'Asia/Tokyo', 'en-GB')).toBe('00:00')
    expect(bucketLabel(point, 'HOURS_24', 'UTC', 'en-GB')).toBe('15:00')
    expect(bucketLabel(point, 'DAYS_7', 'Asia/Tokyo', 'en-GB')).toBe('18/10')
    expect(zoneTime('2026-10-17T15:05:00Z', 'Asia/Tokyo', 'en-GB')).toBe('18/10/2026, 00:05')
    expect(zoneTime(null, 'Asia/Tokyo')).toBe('')
    // An unknown zone falls back to UTC, not to the browser's.
    expect(safeZone('Not/AZone')).toBe('UTC')
  })

  it('read the rows as they come, zeros for missing counts', () => {
    expect(toTrendPoint({ bucketStart: 'x', day: 'd', gap: true })).toMatchObject({ completions: 0, spent: '0', gap: true })
    const d = toDashboard({ live: '2', answering: 5, spentToday: '1200', pendingCount: 1, pendingAmount: '600',
      zone: 'Asia/Tokyo', refreshedAt: '2026-10-18T01:10:00Z' })
    expect(d).toMatchObject({ live: 2, paused: 0, answering: 5, spentToday: '1200', available: '0', zone: 'Asia/Tokyo' })
    // Before the job's first run: no time, UTC.
    expect(toDashboard(undefined)).toMatchObject({ zone: 'UTC', refreshedAt: null })
  })

  it('give no figure of a suppressed question, whatever came with it', () => {
    const hidden = toQuestion({ questionNo: 1, stem: 'Q', points: 2, completions: 4, correct: 4, correctRate: 1,
      difficulty: 'EASY', suppressed: true, basedOn: 4, minGroup: 5 })
    expect(hidden).toMatchObject({ suppressed: true, completions: null, correctRate: null, difficulty: null, minGroup: 5 })
    const shown = toQuestion({ questionNo: '2', stem: 'Q2', points: '3', completions: '10', correct: '4',
      correctRate: '0.4', difficulty: 'HARD', suppressed: false, basedOn: '10', minGroup: '5' })
    expect(shown).toMatchObject({ questionNo: 2, completions: 10, correctRate: 0.4, difficulty: 'HARD' })
  })

  it('give no average time or timeouts below the minimum group, whatever came with them', () => {
    const hidden = toOverview({ publicationId: P1, suppressed: true, avgDurationSec: '41.5', timeouts: 2, zone: 'Asia/Tokyo' })
    expect(hidden).toMatchObject({ suppressed: true, avgDurationSec: null, timeouts: null, zone: 'Asia/Tokyo' })
    expect(toOverview({ publicationId: P1, suppressed: false, avgDurationSec: '41.5', timeouts: 2 }))
      .toMatchObject({ avgDurationSec: 41.5, timeouts: 2, zone: 'UTC' })
  })

  it('read the trend\'s totals as the server gives them', () => {
    expect(toTrendTotals({ completions: '21', spent: '2100', zone: 'Asia/Tokyo', periodStart: 'a', periodEnd: 'b' }))
      .toEqual({ periodStart: 'a', periodEnd: 'b', views: 0, starts: 0, completions: 21, passes: 0, winners: 0,
        spent: '2100', zone: 'Asia/Tokyo' })
    expect(toTrendTotals(undefined)).toMatchObject({ completions: 0, spent: '0', zone: 'UTC' })
  })

  it('build the funnel from the template, marking its largest drop', () => {
    const o = toOverview({ publicationId: P1, name: 'N', status: 'LIVE', funnelViews: 10, starts: 8, completions: 4,
      passes: 3, viewToStart: '0.8', startToComplete: '0.5', completeToPass: '0.75', largestDrop: 'START_COMPLETE',
      suppressed: true, avgDurationSec: null, timeouts: null })
    const stages = funnelOf(o)
    expect(stages.map((s) => [s.key, s.count, s.rate, s.largestDrop])).toEqual([
      ['views', 10, null, false], ['starts', 8, 0.8, false], ['completions', 4, 0.5, true], ['passes', 3, 0.75, false],
    ])
    expect(toOverview({ publicationId: P1, largestDrop: 'SOMETHING' }).largestDrop).toBeNull()
    expect(percent(0.5, 'en')).toBe('50%')
    expect(percent(null)).toBe('—')
  })

  it('find the publication of an analytics address', () => {
    expect(analyticsIdOf(analyticsPath(P1))).toBe(P1)
    expect(analyticsIdOf(`/publications/${P1}`)).toBeNull()
  })
})
