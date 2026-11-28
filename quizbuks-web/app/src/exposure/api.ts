import { runQuery } from '@jabiz/client'
import { maxPerCall, pushEvery, reportBatch, reportIntervalSec, seconds } from './rules'

/** The template of the parameters the App applies (F-S13). */
export const EXPOSURE_SETTINGS = 'qb.app.exposure-settings'

/** The parameters of the exposures and the pushes, bounded as the server bounds them. */
export interface ExposureSettings {
  seconds: number
  pushEvery: number
  reportBatch: number
  reportIntervalSec: number
  /** The most reports one call takes (`qb.exposure.report.max-per-call`). */
  maxPerCall: number
}

const numberOr = (value: unknown): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

export async function readExposureSettings(): Promise<ExposureSettings> {
  const page = await runQuery<Record<string, unknown>>(EXPOSURE_SETTINGS, { limit: 1 })
  const row = page.items[0] ?? {}
  return {
    seconds: seconds(numberOr(row.seconds)),
    pushEvery: pushEvery(numberOr(row.pushEvery)),
    reportBatch: reportBatch(numberOr(row.reportBatch)),
    reportIntervalSec: reportIntervalSec(numberOr(row.reportIntervalSec)),
    maxPerCall: maxPerCall(numberOr(row.maxPerCall)),
  }
}


