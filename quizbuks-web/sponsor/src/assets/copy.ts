import { useState } from 'react'

/**
 * "Chosen is copied" (F-C1b): what a place got from an asset of the library, so that its next save names the asset.
 * The place may be edited afterwards; it then no longer is the asset's copy and the save names no asset (the record
 * of the earlier copy stays: "given none, none changes", docs/quizbuks/plans/impl/F-C1.md F-C1b, risks).
 */
export interface AssetCopy<T> {
  assetId: string
  /** The value the place got. */
  value: T
}

/** Equal as data (the places' values are texts, ids, numbers and plain objects of them). */
export function sameValue(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return false
  if (Array.isArray(a) !== Array.isArray(b)) return false
  const keys = new Set([...Object.keys(a), ...Object.keys(b)])
  return [...keys].every((key) => sameValue((a as Record<string, unknown>)[key], (b as Record<string, unknown>)[key]))
}

/** The asset the place still is a copy of; none when nothing was chosen or the value changed since. */
export function copiedAssetId<T>(copy: AssetCopy<T> | null, current: T): string | null {
  return copy && sameValue(copy.value, current) ? copy.assetId : null
}

/**
 * One place's copy, as a form keeps it: `chose` when an asset was copied in, `idFor` for the save, `saved` after a
 * successful save (the copy was recorded; saving again must not record it anew, which would hide "older content").
 */
export function useAssetCopy<T>() {
  const [copy, setCopy] = useState<AssetCopy<T> | null>(null)
  return {
    chose: (assetId: string, value: T) => setCopy({ assetId, value }),
    idFor: (current: T) => copiedAssetId(copy, current),
    saved: () => setCopy(null),
  }
}
