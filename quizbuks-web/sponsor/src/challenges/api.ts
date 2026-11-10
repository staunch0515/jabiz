import { runProcess, runQuery } from '@jabiz/client'
import type { AttemptResult, SaveInput, SaveOutput, StartOutput, SubmitInput, SubmitOutput } from '../qb/play'
import { graphOf, type Graph } from '../qb/tree/states'
import { profilePath } from '../profile/paths'

/** The template of the Sponsor's challenges (F-S4c, `qb.sponsor.challenges`). */
export const CHALLENGES_TEMPLATE = 'qb.sponsor.challenges'

/** Where the challenges are, where one is answered and where an answered one's result is. */
export const CHALLENGES_PATH = profilePath('challenges')
export const challengePath = (publicationId: string) => `${CHALLENGES_PATH}/${encodeURIComponent(publicationId)}`
export const challengeResultPath = (attemptId: string) =>
  `${CHALLENGES_PATH}/results/${encodeURIComponent(attemptId)}`

/** A challenge bound to a node, and the caller's attempt of it. */
export interface ChallengePublication {
  publicationId: string
  name: string
  translations: Record<string, { name?: string }> | null
  endAt: string | null
  attemptId: string | null
  status: string | null
  deadline: string | null
  /** The caller can start (or continue) it now: one rule with `QB_SPONSOR_ATTEMPT_START`. */
  startable: boolean
}

/** One map of the operator's copies, with what each node grants and the challenges bound to it. */
export interface ChallengeMap {
  graph: Graph
  templateTreeId: string | null
  /** Per node, the granted credit lighting it gives (the template's node's now). */
  grants: Map<string, number>
  publications: Map<string, ChallengePublication[]>
}

export interface Challenges {
  /** The caller's onboarding is approved (otherwise nothing is startable). */
  approved: boolean
  maps: ChallengeMap[]
}

type Row = Record<string, unknown>

function json<T>(value: unknown, fallback: T): T {
  if (value == null) return fallback
  if (typeof value !== 'string') return value as T
  try {
    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

/** The rows of `qb.sponsor.challenges` (one per node) as maps, in the order the template gives them. */
export function challengesOf(rows: Row[]): Challenges {
  const byTree = new Map<string, Row[]>()
  for (const row of rows) {
    const key = String(row.treeId)
    byTree.set(key, [...(byTree.get(key) ?? []), row])
  }
  const maps: ChallengeMap[] = []
  for (const treeRows of byTree.values()) {
    const graph = graphOf(treeRows)
    if (!graph) continue
    const grants = new Map<string, number>()
    const publications = new Map<string, ChallengePublication[]>()
    for (const row of treeRows) {
      const nodeId = String(row.nodeId)
      if (row.grantAmount != null && Number(row.grantAmount) > 0) grants.set(nodeId, Number(row.grantAmount))
      publications.set(nodeId, json<Row[]>(row.publications, []).map((p) => ({
        publicationId: String(p.publicationId),
        name: String(p.name ?? ''),
        translations: json<ChallengePublication['translations']>(p.translations, null),
        endAt: p.endAt == null ? null : String(p.endAt),
        attemptId: p.attemptId == null ? null : String(p.attemptId),
        status: p.status == null ? null : String(p.status),
        deadline: p.deadline == null ? null : String(p.deadline),
        startable: p.startable === true,
      })))
    }
    maps.push({ graph, templateTreeId: treeRows[0].templateTreeId == null ? null : String(treeRows[0].templateTreeId),
      grants, publications })
  }
  return { approved: rows.some((row) => row.approved === true), maps }
}

/** The caller's challenges at `at` (the page's now, by the server's clock). */
export async function readChallenges(at: string): Promise<Challenges> {
  const page = await runQuery<Row>(CHALLENGES_TEMPLATE, { params: { at }, limit: 1000 })
  return challengesOf(page.items)
}

/** A challenge's name in the language shown (its own translation, else the name it was published with). */
export function challengeName(publication: ChallengePublication, language: string): string {
  return publication.translations?.[language.slice(0, 2)]?.name || publication.name
}

// ---- answering (F-S4c: the Taker's inputs and outputs, `play.SponsorPlay`) -----------------------------------------

/** The Sponsor's answering processes (`QB_SPONSOR_ATTEMPT_*`). */
export const CHALLENGE_PROCESS = {
  start: 'QB_SPONSOR_ATTEMPT_START',
  save: 'QB_SPONSOR_ATTEMPT_SAVE',
  submit: 'QB_SPONSOR_ATTEMPT_SUBMIT',
  result: 'QB_SPONSOR_ATTEMPT_RESULT',
} as const

export const startChallenge = (publicationId: string, idempotencyKey: string) =>
  runProcess<StartOutput>(CHALLENGE_PROCESS.start, { publicationId }, { idempotencyKey })
export const saveChallenge = (input: SaveInput) => runProcess<SaveOutput>(CHALLENGE_PROCESS.save, { ...input })
export const submitChallenge = (input: SubmitInput, idempotencyKey: string) =>
  runProcess<SubmitOutput>(CHALLENGE_PROCESS.submit, { ...input }, { idempotencyKey })
export const readChallengeResult = (attemptId: string) =>
  runProcess<AttemptResult>(CHALLENGE_PROCESS.result, { attemptId })

/** The address a path names: a challenge to answer, or an answered one's result; null for another. */
export function challengeRouteOf(path: string): { publicationId: string } | { attemptId: string } | null {
  const prefix = `${CHALLENGES_PATH}/`
  if (!path.startsWith(prefix)) return null
  const rest = path.slice(prefix.length).replace(/\/$/, '')
  if (rest.startsWith('results/')) {
    const attemptId = decodeURIComponent(rest.slice('results/'.length))
    return attemptId && !attemptId.includes('/') ? { attemptId } : null
  }
  const publicationId = decodeURIComponent(rest)
  return publicationId && !publicationId.includes('/') ? { publicationId } : null
}
