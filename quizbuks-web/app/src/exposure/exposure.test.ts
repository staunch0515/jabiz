import { afterEach, describe, expect, it, vi } from 'vitest'
import cases from './exposure-cases.json'
import { configureReporter, flushReports, forgetReports, pendingReports, queueReport, reported } from './reporter'
import { billable, dailyCap, decide, endGraceSec, maxPerCall, minTolerance, neededSec, overshoot, pushAt, pushable,
  pushEvery, reconciles, reportBatch, reportIntervalSec, seconds, tolerancePercent, usedUp,
  type RulesCard } from './rules'
import { NO_RUN, playerRun, screenRun } from './timer'

const client = vi.hoisted(() => ({ runProcess: vi.fn() }))
vi.mock('@jabiz/client', async (original) => ({ ...(await original<typeof import('@jabiz/client')>()),
  runProcess: client.runProcess }))

afterEach(() => {
  forgetReports()
  client.runProcess.mockReset()
  vi.useRealTimers()
})

describe('the rules (F-S13; ExposureRulesTest has the same cases)', () => {
  it('takes the seconds of the parameter, or a shorter video whole', () => {
    for (const c of cases.neededSec) expect(neededSec(c.seconds, c.durationSec)).toBe(c.needed)
  })

  it('decides each report as the server does', () => {
    expect(cases.decide.length).toBeGreaterThan(10)
    for (const c of cases.decide) {
      expect(decide(c.card as RulesCard, c.viewer, c.staffOf, c.surface, c.reportedSec, c.seconds, c.alreadyCounted,
        c.countedToday, c.cap), c.name).toBe(c.outcome)
    }
  })

  it('never pushes what was opened, nor one\'s own or horizontal videos', () => {
    for (const c of cases.pushable) expect(pushable(c.card as RulesCard, c.opened), c.name).toBe(c.pushable)
  })

  it('places the pushes every n-th screen', () => {
    for (const c of cases.pushAt) expect(pushAt(c.offset, c.every)).toBe(c.push)
  })

  it('charges no more than purchased', () => {
    for (const c of cases.billing) {
      expect(usedUp(c.successes, c.purchased)).toBe(c.usedUp)
      expect(billable(c.successes, c.purchased)).toBe(c.billable)
      expect(overshoot(c.successes, c.purchased)).toBe(c.overshoot)
      expect(reconciles(c.successes, c.purchased, c.minTolerance, c.tolerancePercent)).toBe(c.reconciles)
    }
  })

  it('reads the parameters within their bounds', () => {
    const check = (pairs: (number | null)[][], read: (value: number | null) => number) =>
      pairs.forEach(([value, expected]) => expect(read(value)).toBe(expected))
    check(cases.params.seconds, seconds)
    check(cases.params.dailyCap, dailyCap)
    check(cases.params.pushEvery, pushEvery)
    check(cases.params.reportBatch, reportBatch)
    check(cases.params.reportIntervalSec, reportIntervalSec)
    check(cases.params.maxPerCall, maxPerCall)
    check(cases.params.minTolerance, minTolerance)
    check(cases.params.tolerancePercent, tolerancePercent)
    check(cases.params.endGraceSec, endGraceSec)
  })
})

describe('a run without a break (F-S13)', () => {
  it('adds up the player\'s time while it plays, a pause or a seek starts again', () => {
    let run = playerRun(NO_RUN, { type: 'time', currentTime: 0, playing: true, at: 0 })
    run = playerRun(run, { type: 'time', currentTime: 4, playing: true, at: 4000 })
    expect(run.sec).toBeCloseTo(4)
    // A jump ahead beyond twice the clock is a seek: a new run from there.
    run = playerRun(run, { type: 'time', currentTime: 40, playing: true, at: 5000 })
    expect(run.sec).toBe(0)
    run = playerRun(run, { type: 'time', currentTime: 46, playing: true, at: 11_000 })
    expect(run.sec).toBeCloseTo(6)
    // A pause breaks it.
    expect(playerRun(run, { type: 'state', playing: false, at: 11_500 })).toEqual(NO_RUN)
    expect(playerRun(run, { type: 'time', currentTime: 46, playing: false, at: 12_000 })).toEqual(NO_RUN)
    // The end keeps what played (a short video played through).
    expect(playerRun(run, { type: 'ended' }).sec).toBeCloseTo(6)
    expect(playerRun(run, { type: 'suspend' })).toEqual(NO_RUN)
  })

  it('adds up the screen\'s ticks while shown, at most two seconds a tick', () => {
    let run = screenRun(NO_RUN, 0, true)
    run = screenRun(run, 1000, true)
    run = screenRun(run, 2000, true)
    expect(run.sec).toBe(2)
    // A device asleep between ticks does not watch.
    expect(screenRun(run, 60_000, true).sec).toBe(4)
    // Out of view or the page in the background: again from 0.
    expect(screenRun(run, 3000, false)).toEqual(NO_RUN)
  })
})

describe('the reports (F-S13)', () => {
  it('gathers them and sends one call once the batch is full, each publication and kind once', async () => {
    client.runProcess.mockResolvedValue({ results: [], counted: 0 })
    configureReporter({ reportBatch: 2, reportIntervalSec: 30 })
    expect(queueReport({ publicationId: 'p1', kind: 'OPEN', surface: 'VERTICAL' })).toBe(true)
    expect(queueReport({ publicationId: 'p1', kind: 'OPEN', surface: 'VERTICAL' })).toBe(false)
    expect(client.runProcess).not.toHaveBeenCalled()
    queueReport({ publicationId: 'p1', kind: 'EXPOSURE', surface: 'VERTICAL', seconds: 10, timer: 'PLAYER' })
    await vi.waitFor(() => expect(client.runProcess).toHaveBeenCalledTimes(1))
    expect(client.runProcess).toHaveBeenCalledWith('QB_EXPOSURE_REPORT', { reports: [
      { publicationId: 'p1', kind: 'OPEN', surface: 'VERTICAL' },
      { publicationId: 'p1', kind: 'EXPOSURE', surface: 'VERTICAL', seconds: 10, timer: 'PLAYER' }] })
    expect(reported('p1', 'EXPOSURE')).toBe(true)
  })

  it('sends what waits once the interval passed', async () => {
    vi.useFakeTimers()
    client.runProcess.mockResolvedValue({ results: [], counted: 0 })
    configureReporter({ reportBatch: 20, reportIntervalSec: 5 })
    queueReport({ publicationId: 'p2', kind: 'GO', surface: 'START' })
    await vi.advanceTimersByTimeAsync(4_900)
    expect(client.runProcess).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(200)
    expect(client.runProcess).toHaveBeenCalledTimes(1)
  })

  it('sends no more in one call than the parameter says', async () => {
    client.runProcess.mockResolvedValue({ results: [], counted: 0 })
    configureReporter({ reportBatch: 50, reportIntervalSec: 30, maxPerCall: 2 })
    queueReport({ publicationId: 'q1', kind: 'OPEN', surface: 'VERTICAL' })
    queueReport({ publicationId: 'q2', kind: 'OPEN', surface: 'VERTICAL' })
    await vi.waitFor(() => expect(client.runProcess).toHaveBeenCalledTimes(1))
    queueReport({ publicationId: 'q3', kind: 'OPEN', surface: 'VERTICAL' })
    await flushReports()
    expect(client.runProcess.mock.calls.map((call) => (call[1] as { reports: unknown[] }).reports.length))
      .toEqual([2, 1])
  })

  it('keeps the reports of a failed call for the next, drops those the server refused', async () => {
    client.runProcess.mockRejectedValueOnce(Object.assign(new Error('down'), { status: 503 }))
    queueReport({ publicationId: 'p3', kind: 'OPEN', surface: 'VERTICAL' })
    await flushReports()
    expect(pendingReports()).toHaveLength(1)
    client.runProcess.mockRejectedValueOnce(Object.assign(new Error('bad'), { status: 400 }))
    await flushReports()
    expect(pendingReports()).toHaveLength(0)
  })
})


describe('reports left by an earlier page (F-S13)', () => {
  it('are kept in the tab until the server has them, and sent by the next page', async () => {
    vi.useFakeTimers()
    client.runProcess.mockReturnValue(new Promise(() => {}))
    queueReport({ publicationId: 'p9', kind: 'OPEN', surface: 'VERTICAL' })
    void flushReports()
    // The call is on its way when the page goes: still kept.
    expect(JSON.parse(window.sessionStorage.getItem('qb.exposure.pending') ?? '[]')).toHaveLength(1)
    // The next page (the module afresh) sends it.
    const { resumeReports, forgetReports: forget } = await import('./reporter')
    forget()
    window.sessionStorage.setItem('qb.exposure.pending', JSON.stringify([{ publicationId: 'p9', kind: 'OPEN',
      surface: 'VERTICAL' }]))
    client.runProcess.mockReset().mockResolvedValue({ results: [], counted: 0 })
    resumeReports()
    await vi.advanceTimersByTimeAsync(5_100)
    expect(client.runProcess).toHaveBeenCalledWith('QB_EXPOSURE_REPORT', { reports: [{ publicationId: 'p9',
      kind: 'OPEN', surface: 'VERTICAL' }] })
    expect(window.sessionStorage.getItem('qb.exposure.pending')).toBeNull()
  })
})
