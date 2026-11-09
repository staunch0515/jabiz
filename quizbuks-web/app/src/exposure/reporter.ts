import { runProcess } from '@jabiz/client'
import { DEFAULT_MAX_PER_CALL, DEFAULT_REPORT_BATCH, DEFAULT_REPORT_INTERVAL_SEC, MAX_BATCH, maxPerCall, reportBatch,
  reportIntervalSec,
  type ReportKind, type Surface, type Timer } from './rules'

/*
 * F-S13: the App gathers its reports of exposures and sends them in one call of `QB_EXPOSURE_REPORT` (rate-limited on
 * the server) once `qb.exposure.report.batch` of them are waiting or `qb.exposure.report.interval-sec` has passed since
 * the first, whichever comes first, and at once when the page goes to the background. A report is made once per
 * publication and kind in this tab (the server counts once per taker and publication anyway). A failed call keeps its
 * reports for the next time, unless the server refused them (a 4xx other than 429: nothing to gain from repeating).
 * What is not sent yet is also kept in this tab's sessionStorage until the server has it: a call cut short by leaving
 * the page (a reload, a link) is sent by the next page. Storage can be missing or refuse: every access is guarded.
 */

export const REPORT_PROCESS = 'QB_EXPOSURE_REPORT'

export interface ExposureReport {
  publicationId: string
  kind: ReportKind
  surface: Surface
  seconds?: number
  timer?: Timer
}

let batch = DEFAULT_REPORT_BATCH
let intervalMs = DEFAULT_REPORT_INTERVAL_SEC * 1000
let perCall = DEFAULT_MAX_PER_CALL
const queue: ExposureReport[] = []
const made = new Set<string>()
let timer: ReturnType<typeof setTimeout> | null = null
let sending: Promise<void> | null = null
let listening = false

const KEY = 'qb.exposure.pending'
/** The reports of the call on its way (kept until it is answered). */
let inFlight: ExposureReport[] = []

function save() {
  try {
    const pending = [...inFlight, ...queue]
    if (pending.length === 0) window.sessionStorage.removeItem(KEY)
    else window.sessionStorage.setItem(KEY, JSON.stringify(pending))
  } catch {
    // Kept in memory only.
  }
}

function restore(): ExposureReport[] {
  try {
    const text = window.sessionStorage.getItem(KEY)
    const kept = text ? JSON.parse(text) as unknown : null
    return Array.isArray(kept) ? kept.filter((r): r is ExposureReport => typeof r === 'object' && r !== null
      && typeof (r as ExposureReport).publicationId === 'string').slice(0, MAX_BATCH * 4) : []
  } catch {
    return []
  }
}

const keyOf = (report: Pick<ExposureReport, 'publicationId' | 'kind'>) => `${report.publicationId}:${report.kind}`

/** The parameters as read (`qb.app.exposure-settings`), bounded as the server bounds them. */
export function configureReporter(settings: { reportBatch?: number | null; reportIntervalSec?: number | null;
  maxPerCall?: number | null }) {
  batch = reportBatch(settings.reportBatch)
  intervalMs = reportIntervalSec(settings.reportIntervalSec) * 1000
  perCall = maxPerCall(settings.maxPerCall)
}

/** Whether this tab reported that already (an open, an exposure, going on to answer). */
export function reported(publicationId: string, kind: ReportKind): boolean {
  return made.has(keyOf({ publicationId, kind }))
}

/** Gathers a report; false when this tab made it already. */
export function queueReport(report: ExposureReport): boolean {
  const key = keyOf(report)
  if (made.has(key)) return false
  made.add(key)
  queue.push(report)
  save()
  listen()
  if (queue.length >= Math.min(batch, perCall)) {
    void flushReports()
  } else if (!timer) {
    timer = setTimeout(() => {
      timer = null
      void flushReports()
    }, intervalMs)
  }
  return true
}

/** Sends what is waiting (at most a call's worth at a time, the rest after it); nothing waiting: nothing sent. */
export async function flushReports(): Promise<void> {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
  if (sending) await sending
  if (queue.length === 0) return
  const reports = queue.splice(0, perCall)
  inFlight = reports
  sending = runProcess(REPORT_PROCESS, { reports }).then(() => undefined, (error: unknown) => {
    const status = (error as { status?: number } | null)?.status
    if (status == null || status >= 500 || status === 429) queue.unshift(...reports)
  }).finally(() => {
    inFlight = []
    sending = null
    save()
  })
  await sending
  if (queue.length >= Math.min(batch, perCall)) await flushReports()
  else if (queue.length > 0 && !timer) {
    timer = setTimeout(() => {
      timer = null
      void flushReports()
    }, intervalMs)
  }
}

/** Forgets every report (on signing out, and between tests). */
export function forgetReports() {
  queue.length = 0
  inFlight = []
  sending = null
  save()
  made.clear()
  if (timer) clearTimeout(timer)
  timer = null
  batch = DEFAULT_REPORT_BATCH
  intervalMs = DEFAULT_REPORT_INTERVAL_SEC * 1000
  perCall = DEFAULT_MAX_PER_CALL
}

/** What is waiting to be sent (for tests). */
export function pendingReports(): readonly ExposureReport[] {
  return [...queue]
}

function listen() {
  if (listening || typeof document === 'undefined') return
  listening = true
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void flushReports()
  })
  window.addEventListener('pagehide', () => void flushReports())
}

/** What an earlier page of this tab left unsent goes with the next call (or after the interval). */
export function resumeReports() {
  const kept = restore()
  if (kept.length === 0) return
  for (const report of kept) {
    const key = keyOf(report)
    if (!made.has(key)) {
      made.add(key)
      queue.push(report)
    }
  }
  listen()
  if (!timer) {
    timer = setTimeout(() => {
      timer = null
      void flushReports()
    }, Math.min(intervalMs, 5_000))
  }
}

if (typeof window !== 'undefined') resumeReports()
