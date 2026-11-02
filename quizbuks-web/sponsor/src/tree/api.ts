import { runQuery } from '@jabiz/client'
import { edgesOf, readAllRows, treeStateOf, type TreeEdge, type TreeState } from '../qb/tree'
import { TREE_TEMPLATE } from './ids'

/*
 * The sponsor's tree (Q85): typed by hand after the templates qb.sponsor.tree / -people / -person. Nothing is
 * written: the tree is the publications and their prerequisites, the states follow from the takers' attempts.
 * A customer shows by their profile's name and the key the transactions page filters by, never their id.
 */

export const TREE_PATH = '/tree'
export const TREE_QUERY = ['qb', 'sponsor-tree'] as const

/** The tree page with a node marked. */
export const treePathOf = (publicationId: string) => `${TREE_PATH}?node=${encodeURIComponent(publicationId)}`

/** The node an address marks, or null. */
export function nodeOf(search: string): string | null {
  const node = new URLSearchParams(search).get('node')?.trim()
  return node || null
}

export interface TreeRow {
  id: string
  name: string
  status: string
  startAt: string
  endAt: string
  live: boolean
  isolated: boolean
  requires: TreeEdge[]
  counts: { lit: number; answered: number; inProgress: number; unlocked: number; locked: number }
}

/** The states a node's people are listed by (closed is nobody's list: nobody can do anything there). */
export const PEOPLE_STATES: readonly TreeState[] = ['LIT', 'ANSWERED', 'IN_PROGRESS', 'UNLOCKED', 'LOCKED']

export interface Person {
  userKey: string
  userName: string | null
  state: TreeState
  finishedAt: string | null
  hasConversation: boolean
}

export const PEOPLE_PAGE = 50

type Row = Record<string, unknown>

const bool = (value: unknown) => value === true || value === 'true'
const num = (value: unknown) => (typeof value === 'number' ? value : Number(value ?? 0)) || 0
const str = (value: unknown) => (typeof value === 'string' && value !== '' ? value : null)

export async function readTree(at: string): Promise<TreeRow[]> {
  const rows = await readAllRows((offset, limit) => runQuery<Row>(TREE_TEMPLATE.tree, { params: { at }, offset, limit }))
  return rows.flatMap((row): TreeRow[] => typeof row.publicationId !== 'string' ? [] : [{
    id: row.publicationId,
    name: String(row.name ?? ''),
    status: String(row.status ?? ''),
    startAt: String(row.startAt ?? ''),
    endAt: String(row.endAt ?? ''),
    live: bool(row.live),
    isolated: bool(row.isolated),
    requires: edgesOf(row.prerequisites),
    counts: { lit: num(row.lit), answered: num(row.answered), inProgress: num(row.inProgress),
      unlocked: num(row.unlocked), locked: num(row.locked) },
  }])
}

export async function readPeople(publicationId: string, state: TreeState, at: string, offset = 0):
  Promise<{ items: Person[]; offset: number }> {
  const page = await runQuery<Row>(TREE_TEMPLATE.people, { params: { publicationId, state, at }, offset,
    limit: PEOPLE_PAGE })
  return { offset, items: page.items.flatMap((row): Person[] => {
    const found = treeStateOf(row.state)
    return typeof row.userKey !== 'string' || !found ? [] : [{ userKey: row.userKey, userName: str(row.userName),
      state: found, finishedAt: str(row.finishedAt), hasConversation: bool(row.hasConversation) }]
  }) }
}

export interface PersonTree {
  userName: string | null
  states: Record<string, TreeState>
}

/** One customer's states on every node, or null when the key is nobody's. */
export async function readPerson(userKey: string, at: string): Promise<PersonTree | null> {
  const rows = await readAllRows((offset, limit) => runQuery<Row>(TREE_TEMPLATE.person,
    { params: { userKey, at }, offset, limit }))
  if (rows.length === 0) return null
  const states: Record<string, TreeState> = {}
  for (const row of rows) {
    const state = treeStateOf(row.state)
    if (typeof row.publicationId === 'string' && state) states[row.publicationId] = state
  }
  return { userName: str(rows[0].userName), states }
}

/** A transactions-page key as the sponsor may paste it: 32 hex digits. */
export const USER_KEY = /^[0-9a-f]{32}$/i
