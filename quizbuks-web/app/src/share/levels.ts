import { prerequisitesOf } from '../qb/play'

/**
 * Q76 (D-Q76-8): the levels a sponsor's page draws from what its listed publications require of each other. A
 * publication requiring none of the listed ones is on the first level; one requiring others is one level above the
 * highest of them. Only listed publications count (the template names no other); a cycle (none expected: the server
 * refuses one) breaks at the publication first met. Within a level the list's order is kept.
 */
export interface Level<T> {
  /** 1 for the first level. */
  level: number
  items: T[]
}

export function levelsOf<T extends { publicationId: string; prerequisites?: unknown }>(items: readonly T[]): Level<T>[] {
  const listed = new Set(items.map((item) => item.publicationId))
  const requires = new Map(items.map((item) => [item.publicationId, prerequisitesOf(item.prerequisites)
    .map((p) => p.requiredPublicationId).filter((id) => listed.has(id) && id !== item.publicationId)]))
  const known = new Map<string, number>()
  const levelOf = (id: string, walking: Set<string>): number => {
    const found = known.get(id)
    if (found !== undefined) return found
    if (walking.has(id)) return 1
    walking.add(id)
    const level = 1 + Math.max(0, ...(requires.get(id) ?? []).map((required) => levelOf(required, walking)))
    walking.delete(id)
    known.set(id, level)
    return level
  }
  const groups = new Map<number, T[]>()
  for (const item of items) {
    const level = levelOf(item.publicationId, new Set())
    groups.set(level, [...(groups.get(level) ?? []), item])
  }
  return [...groups.entries()].sort(([a], [b]) => a - b).map(([level, grouped]) => ({ level, items: grouped }))
}
