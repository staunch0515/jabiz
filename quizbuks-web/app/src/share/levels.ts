/*
 * Q76 (docs/quizbuks/plans/Q76-prerequisites.md D-Q76-8): the sponsor's page lays its live publications out by their
 * prerequisites: those requiring none first, then each one a layer below the deepest it requires. A prerequisite not
 * on the page (not live, not public) counts as none; a cycle (the server refuses them, D-Q76-4) is cut where it is
 * met, so every publication gets a layer.
 */

/** A publication's prerequisites as `qb.public.sponsor-page-publications` gives them (its JSON column). */
export interface PagePrerequisite {
  publicationId: string
  requirement: 'PASSED' | 'ANSWERED'
}

export function parsePagePrerequisites(json: unknown): PagePrerequisite[] {
  if (typeof json !== 'string' || json.trim() === '') return []
  try {
    const list: unknown = JSON.parse(json)
    if (!Array.isArray(list)) return []
    return list.filter((p): p is Record<string, unknown> => typeof p === 'object' && p !== null && !!p.publicationId)
      .map((p) => ({ publicationId: String(p.publicationId), requirement: p.requirement === 'ANSWERED' ? 'ANSWERED'
        : 'PASSED' }))
  } catch {
    return []
  }
}

/** Each publication's layer (0: requires none of those listed), by id. */
export function layersOf(publications: readonly { publicationId: string; prerequisites: PagePrerequisite[] }[]):
  Map<string, number> {
  const required = new Map(publications.map((p) => [p.publicationId, p.prerequisites.map((r) => r.publicationId)]))
  const layers = new Map<string, number>()
  const layer = (id: string, path: Set<string>): number => {
    const known = layers.get(id)
    if (known !== undefined) return known
    if (path.has(id)) return -1
    path.add(id)
    let deepest = -1
    for (const next of required.get(id) ?? []) {
      if (required.has(next)) deepest = Math.max(deepest, layer(next, path))
    }
    path.delete(id)
    const result = deepest + 1
    layers.set(id, result)
    return result
  }
  publications.forEach((p) => layer(p.publicationId, new Set()))
  return layers
}

/** The publications grouped by layer, in the given order within each; one group when none requires another. */
export function grouped<P extends { publicationId: string; prerequisites: PagePrerequisite[] }>(
  publications: readonly P[]): P[][] {
  const layers = layersOf(publications)
  const groups: P[][] = []
  for (const publication of publications) {
    const at = layers.get(publication.publicationId) ?? 0
    while (groups.length <= at) groups.push([])
    groups[at].push(publication)
  }
  return groups.filter((group) => group.length > 0)
}
