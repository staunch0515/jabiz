import { afterEach, describe, expect, it } from 'vitest'
import { clock, EMPTY, forgetPlayback, needed, playbackOf, recordPlayback, reduce, satisfied, shortfallSec, toInput,
  type PlaybackRecord } from './playback'

/** The record after these reports, each `[currentTime, playing, at]`, starting to play at `start`. */
function watched(reports: [number, boolean, number][], start: PlaybackRecord = reduce(EMPTY, { type: 'state', playing: true, at: 0 })) {
  return reports.reduce((record, [currentTime, playing, at]) => reduce(record, { type: 'time', currentTime, playing, at }),
    start)
}

afterEach(() => forgetPlayback())

describe('the seconds watched (Q75, D-Q75-2)', () => {
  it('counts consecutive reports while playing, at most twice the clock', () => {
    // Half-second reports at normal pace: 4 s of video over 4 s.
    const normal = watched([[0, true, 0], [0.5, true, 500], [1, true, 1000], [2, true, 2000], [4, true, 4000]])
    expect(normal.watchedSec).toBeCloseTo(4)
    // At 2×: 8 s of video over 4 s all count.
    const fast = watched([[0, true, 0], [8, true, 4000]])
    expect(fast.watchedSec).toBeCloseTo(8)
    // At 3×: a jump of 12 s over 4 s is beyond the pace (and the second of slack): a seek, not watching.
    const seek = watched([[0, true, 0], [12, true, 4000]])
    expect(seek.watchedSec).toBe(0)
    // Skipping back does not count either; counting goes on from there.
    const back = watched([[0, true, 0], [10, true, 10_000], [2, true, 11_000], [3, true, 12_000]])
    expect(back.watchedSec).toBeCloseTo(11)
  })

  it('does not count while paused, nor a seek made while paused', () => {
    let record = watched([[0, true, 0], [5, true, 5000]])
    record = reduce(record, { type: 'state', playing: false, at: 5000 })
    // Paused reports and a jump to 50 s: nothing; playing again anchors afresh, then counts.
    record = watched([[5, false, 6000], [50, false, 20_000]], record)
    record = reduce(record, { type: 'state', playing: true, at: 21_000 })
    record = watched([[50, true, 21_000], [52, true, 23_000]], record)
    expect(record.watchedSec).toBeCloseTo(7)
    expect(record.status).toBe('REPORTED')
  })

  it('remembers the end, the duration, and a player never heard from', () => {
    expect(reduce(EMPTY, { type: 'ended' })).toMatchObject({ ended: true, playing: false, status: 'REPORTED' })
    expect(reduce(EMPTY, { type: 'duration', durationSec: 130 }).durationSec).toBe(130)
    expect(reduce(EMPTY, { type: 'duration', durationSec: 0 }).durationSec).toBeNull()
    expect(reduce(EMPTY, { type: 'unknown' }).status).toBe('UNKNOWN')
    // Once heard, the wait's verdict changes nothing.
    expect(reduce(reduce(EMPTY, { type: 'ended' }), { type: 'unknown' }).status).toBe('REPORTED')
    // A suspension (the card's muted playback stops counting) drops the anchor only.
    const suspended = reduce(watched([[0, true, 0], [3, true, 3000]]), { type: 'suspend' })
    expect(suspended).toMatchObject({ watchedSec: 3, playing: false, lastTime: null })
  })
})

describe('the start\'s rule (Q75, D-Q75-4, D-Q75-6; WatchRulesTest has the same cases)', () => {
  const reported = (watchedSec: number, ended = false): PlaybackRecord => ({ ...EMPTY, status: 'REPORTED', watchedSec, ended })

  it('needs nine tenths of the duration, rounded up', () => {
    expect(needed(100)).toBe(90)
    expect(needed(95)).toBe(86)
    expect(needed(1)).toBe(1)
    expect(needed(null)).toBeNull()
  })

  it('is satisfied by enough watched or the end, let through when the player could not be read', () => {
    expect(satisfied(reported(90), 100)).toBe(true)
    expect(satisfied(reported(89.9), 100)).toBe(false)
    expect(satisfied(reported(0, true), 100)).toBe(true)
    expect(satisfied(EMPTY, 100)).toBe(false)
    expect(satisfied({ ...EMPTY, status: 'UNKNOWN' }, 100)).toBe(true)
    // Without a duration only the end counts.
    expect(satisfied(reported(3600), null)).toBe(false)
    expect(satisfied(reported(3600, true), null)).toBe(true)
  })

  it('says how much is still to watch', () => {
    expect(shortfallSec(reported(30), 100)).toBe(60)
    expect(shortfallSec(EMPTY, 100)).toBe(90)
    expect(shortfallSec(reported(90), 100)).toBe(0)
    expect(shortfallSec(reported(30), null)).toBe(0)
  })

  it('reports whole seconds, the end and the player\'s duration; nothing before the player was heard', () => {
    expect(toInput(EMPTY)).toBeNull()
    expect(toInput({ ...EMPTY, status: 'UNKNOWN' })).toEqual({ status: 'UNKNOWN' })
    expect(toInput({ ...reported(95.7, true), durationSec: 100.4 }))
      .toEqual({ status: 'REPORTED', watchedSec: 95, ended: true, reportedDurationSec: 100 })
    expect(clock(95)).toBe('1:35')
    expect(clock(5)).toBe('0:05')
  })
})

describe('the records of this tab', () => {
  it('keeps a record per publication across pages and forgets them on signing out', () => {
    recordPlayback('pub-1', { type: 'state', playing: true, at: 0 })
    recordPlayback('pub-1', { type: 'time', currentTime: 0, playing: true, at: 0 })
    recordPlayback('pub-1', { type: 'time', currentTime: 4, playing: true, at: 4000 })
    recordPlayback('pub-1', { type: 'duration', durationSec: 60 })
    expect(playbackOf('pub-1')).toMatchObject({ watchedSec: 4, durationSec: 60, status: 'REPORTED' })
    expect(playbackOf('pub-2')).toEqual(EMPTY)
    // What the storage keeps for the next page: the figures, not the anchor.
    expect(JSON.parse(window.sessionStorage.getItem('qb.play.playback.pub-1')!))
      .toEqual({ watchedSec: 4, ended: false, durationSec: 60, status: 'REPORTED' })
    forgetPlayback()
    expect(playbackOf('pub-1')).toEqual(EMPTY)
    expect(window.sessionStorage.getItem('qb.play.playback.pub-1')).toBeNull()
  })
})
