import { runQuery } from '@jabiz/client'
import { ASSET_TEMPLATE } from './ids'

/*
 * The usage shown on the asset library (F-C2, docs/quizbuks/plans/impl/F-C2.md): the latest daily snapshot of what the
 * sponsor occupies (`qb.sponsor.usage`), and each asset's size and places (`qb.sponsor.asset-stats`). Only shown and
 * noticed: nothing is refused or charged in the first launch.
 */

export const USAGE_QUERY = ['qb', 'my-usage'] as const
export const ASSET_STATS_QUERY = ['qb', 'my-asset-stats'] as const

/** The usage card: the latest snapshot's day and figures (none before the first), and the free amount now. */
export interface Usage {
  /** The day of the snapshot (YYYY-MM-DD); none before the first run. */
  day: string | null
  storageBytes: number | null
  storageFiles: number | null
  assetsCount: number | null
  /** The free storage in bytes; none when the parameter is not set. */
  freeBytes: number | null
  /** Above the free amount (`UsageRules.over`): the banner shows. */
  overFree: boolean
}

type Row = Record<string, unknown>
const number = (value: unknown) => (value == null || value === '' ? null : Number(value))

export async function readUsage(): Promise<Usage> {
  const row = (await runQuery<Row>(ASSET_TEMPLATE.usage, { limit: 1 })).items[0] ?? {}
  return {
    day: row.day == null || row.day === '' ? null : String(row.day),
    storageBytes: number(row.storageBytes),
    storageFiles: number(row.storageFiles),
    assetsCount: number(row.assetsCount),
    freeBytes: number(row.freeBytes),
    overFree: row.overFree === true || row.overFree === 'true',
  }
}

/** An asset's size (its file as uploaded and its texts, in bytes) and how many places it was copied to. */
export interface AssetStats {
  fileBytes: number
  textBytes: number
  uses: number
}

/** By asset id. A library holds at most `qb.assets.max-count` (1,000 by default) assets: read a page at a time. */
export async function readAssetStats(): Promise<Map<string, AssetStats>> {
  const stats = new Map<string, AssetStats>()
  const page = 500
  for (let offset = 0; ; offset += page) {
    const items = (await runQuery<Row>(ASSET_TEMPLATE.stats, { limit: page, offset })).items
    for (const row of items) {
      stats.set(String(row.assetId), {
        fileBytes: Number(row.fileBytes) || 0,
        textBytes: Number(row.textBytes) || 0,
        uses: Number(row.uses) || 0,
      })
    }
    if (items.length < page) break
  }
  return stats
}

/** An asset's size: its file and its texts. */
export const sizeOf = (stats: AssetStats | undefined) => (stats ? stats.fileBytes + stats.textBytes : 0)

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'] as const

/** Bytes in the largest unit below 1,024 of it (1 KB = 1,024 bytes, as the server's notice): "1.5 MB", "300 B". */
export function formatBytes(bytes: number | null | undefined): string {
  let value = Math.max(0, bytes ?? 0)
  let unit = 0
  while (value >= 1024 && unit < UNITS.length - 1) {
    value /= 1024
    unit += 1
  }
  return unit === 0 ? `${Math.round(value)} B` : `${value.toFixed(1)} ${UNITS[unit]}`
}

/** How much of the free amount is used, 0 to 100 (more than the amount is shown full); none without an amount. */
export function usedPercent(usage: Pick<Usage, 'storageBytes' | 'freeBytes'>): number | null {
  if (usage.freeBytes == null || usage.freeBytes <= 0) return null
  return Math.min(100, Math.round(((usage.storageBytes ?? 0) / usage.freeBytes) * 100))
}
