import type { HistoryVersion } from './types'

/**
 * Pure helpers of the history timeline (docs/design/04-temporal-append-only.md section 5.2).
 */

/** Versions newest first (by version number: the order they were recorded). */
export function newestFirst(versions: HistoryVersion[]): HistoryVersion[] {
  return [...versions].sort((a, b) => b.versionNo - a.versionNo)
}

/** A version that takes effect later than now: a scheduled change. */
export function isScheduled(version: HistoryVersion, now: Date): boolean {
  return new Date(version.effectStartTime).getTime() > now.getTime()
}

/** The version a change was based on: its base version, else the one recorded just before it. */
export function baseOf(versions: HistoryVersion[], version: HistoryVersion): HistoryVersion | undefined {
  if (version.baseVersionNo != null) return versions.find((v) => v.versionNo === version.baseVersionNo)
  return versions
    .filter((v) => v.versionNo < version.versionNo)
    .sort((a, b) => b.versionNo - a.versionNo)[0]
}

export interface FieldChange {
  field: string
  before: unknown
  after: unknown
}

/**
 * What a version changed: its changed fields with the value before (in its base version) and after. Fields whose
 * values are not returned (sensitive ones) show as changed without values.
 */
export function changesOf(versions: HistoryVersion[], version: HistoryVersion): FieldChange[] {
  const base = baseOf(versions, version)
  return version.changedFields.map((field) => ({
    field,
    before: base?.attributes?.[field],
    after: version.attributes?.[field],
  }))
}

/** The names of the fields whose value at a point in time differs from the current one. */
export function differingFields(
  then: Record<string, unknown> | null | undefined,
  now: Record<string, unknown> | null | undefined,
): Set<string> {
  const names = new Set([...Object.keys(then ?? {}), ...Object.keys(now ?? {})])
  const differing = new Set<string>()
  for (const name of names) {
    if (JSON.stringify(then?.[name] ?? null) !== JSON.stringify(now?.[name] ?? null)) differing.add(name)
  }
  return differing
}
