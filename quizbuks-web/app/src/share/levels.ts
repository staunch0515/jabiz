import { prerequisitesOf } from '../qb/play'
import { levelsOf as treeLevels } from '../qb/tree'

/**
 * Q76 (D-Q76-8): the levels a sponsor's page draws from what its listed publications require of each other. A
 * publication requiring none of the listed ones is on the first level; one requiring others is one level above the
 * highest of them (the tree's own rule, `qb/tree.ts`, since Q85). Only listed publications count (the template names
 * no other); a cycle (none expected: the server refuses one) breaks at the publication first met. Within a level the
 * list's order is kept.
 */
export interface Level<T> {
  /** 1 for the first level. */
  level: number
  items: T[]
}

export function levelsOf<T extends { publicationId: string; prerequisites?: unknown }>(items: readonly T[]): Level<T>[] {
  const levels = treeLevels(items.map((item) => ({ id: item.publicationId, name: '',
    requires: prerequisitesOf(item.prerequisites).map((p) => ({ id: p.requiredPublicationId, requirement: p.requirement })) })))
  const groups = new Map<number, T[]>()
  for (const item of items) {
    const level = levels.get(item.publicationId) ?? 1
    groups.set(level, [...(groups.get(level) ?? []), item])
  }
  return [...groups.entries()].sort(([a], [b]) => a - b).map(([level, grouped]) => ({ level, items: grouped }))
}
