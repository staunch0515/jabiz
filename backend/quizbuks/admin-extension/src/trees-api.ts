import { runProcess, runQuery } from '@jabiz/admin'
import type { Page, SortSpec } from './api'
import { PROCESS, TEMPLATE } from './ids'

type Row = Record<string, unknown>

const str = (value: unknown): string | null => (value == null || value === '' ? null : String(value))
const num = (value: unknown): number => {
  const n = Number(value ?? 0)
  return Number.isFinite(n) ? n : 0
}

export const TREES_KEY = ['qb-ext', 'trees'] as const

/** A multilingual text (`{"en": …}` as JSON) in the reader's language, else the first one there is. */
export function i18nText(json: string | null, language: string): string {
  if (!json) return ''
  try {
    const texts = JSON.parse(json) as Record<string, string | null>
    const own = texts[language.slice(0, 2)]
    if (own) return own
    return Object.values(texts).find((text) => !!text) ?? ''
  } catch {
    return json
  }
}

/** A row of `qb.admin.trees` / `qb.operator.trees`. */
export interface TreeRow {
  treeId: string
  ownerKind: string
  ownerId: string | null
  subjectKind: string
  status: string
  templateTreeId: string | null
  currentVersionId: string | null
  name: string | null
  versionNo: number | null
  openVersionId: string | null
  openStatus: string | null
  openVersionNo: number | null
  approvalRequestId: string | null
  submittedAt: string | null
  remindedAt: string | null
  nodes: number
  litSubjects: number
}

function treeRow(row: Row): TreeRow {
  return {
    treeId: String(row.treeId), ownerKind: String(row.ownerKind ?? ''), ownerId: str(row.ownerId),
    subjectKind: String(row.subjectKind ?? ''), status: String(row.status ?? ''), templateTreeId: str(row.templateTreeId),
    currentVersionId: str(row.currentVersionId),
    name: str(row.name), versionNo: row.versionNo == null ? null : num(row.versionNo), openVersionId: str(row.openVersionId),
    openStatus: str(row.openStatus), openVersionNo: row.openVersionNo == null ? null : num(row.openVersionNo),
    approvalRequestId: str(row.approvalRequestId), submittedAt: str(row.submittedAt), remindedAt: str(row.remindedAt),
    nodes: num(row.nodes), litSubjects: num(row.litSubjects),
  }
}

/** The maps the caller looks after, a page at a time: every one for the platform, an operator's own for its members. */
export async function readTrees(operator: boolean, offset: number, limit: number,
  sorts?: SortSpec[]): Promise<Page<TreeRow>> {
  const page = await runQuery<Row>(operator ? TEMPLATE.operatorTrees : TEMPLATE.trees,
    { sorts, offset, limit, count: true })
  return { total: page.total, items: page.items.map(treeRow) }
}

/** One map, as the list gives it (none: not one the caller looks after). */
export async function readTree(operator: boolean, treeId: string): Promise<TreeRow | null> {
  const page = await runQuery<Row>(operator ? TEMPLATE.operatorTrees : TEMPLATE.trees,
    { filters: [{ field: 'treeId', op: 'eq', value: treeId }], limit: 1 })
  return page.items.length === 0 ? null : treeRow(page.items[0])
}

export type NodeChange = 'ADDED' | 'CHANGED' | 'SAME' | 'REMOVED'

/** A node of a version against the version in effect (`qb.admin.tree-version`). */
export interface VersionNode {
  nodeId: string
  change: NodeChange | string
  name: string | null
  description: string | null
  condition: string | null
  runningBound: number
}

export async function readTreeVersion(versionId: string): Promise<VersionNode[]> {
  const page = await runQuery<Row>(TEMPLATE.treeVersion, { params: { versionId }, limit: 1000 })
  return page.items.map((row) => ({ nodeId: String(row.nodeId), change: String(row.change ?? ''), name: str(row.name),
    description: str(row.description),
    condition: row.condition == null ? null : typeof row.condition === 'string' ? row.condition : JSON.stringify(row.condition), runningBound: num(row.runningBound) }))
}

/** A lit record of the map (`qb.admin.tree-lit`), with its revocation and the request to revoke it in review. */
export interface LitRecord {
  litId: string
  subjectKind: string
  subjectId: string
  nodeId: string
  litAt: string | null
  revokedAt: string | null
  revokeReason: string | null
  requestStatus: string | null
  approvalRequestId: string | null
  requestedBy: string | null
}

export async function readTreeLit(treeId: string, offset: number, limit: number): Promise<Page<LitRecord>> {
  const page = await runQuery<Row>(TEMPLATE.treeLit, { params: { treeId }, offset, limit, count: true })
  return {
    total: page.total,
    items: page.items.map((row) => ({ litId: String(row.litId), subjectKind: String(row.subjectKind ?? ''),
      subjectId: String(row.subjectId ?? ''), nodeId: String(row.nodeId ?? ''), litAt: str(row.litAt),
      revokedAt: str(row.revokedAt), revokeReason: str(row.revokeReason), requestStatus: str(row.requestStatus),
      approvalRequestId: str(row.approvalRequestId), requestedBy: str(row.requestedBy) })),
  }
}

/** Asks another member to approve revoking the lit record (`QB_TREE_LIT_REVOKE`); nothing is revoked before it. */
export const revokeLit = (litId: string, reason: string) =>
  runProcess<{ requestId: string; litId: string; status: string; approvalRequestId: string | null }>(
    PROCESS.treeLitRevoke, { litId, reason })

/** A node's condition (`TreeCondition` as JSON), as its parts: groups, nodes to be lit, and the pass. */
export type Condition =
  | { kind: 'group'; op: 'ALL' | 'ANY' | 'AT_LEAST'; n: number; items: Condition[] }
  | { kind: 'node'; nodeId: string; beforeAnswer: boolean }
  | { kind: 'passed' }

export function parseCondition(json: string | null): Condition | null {
  if (!json) return null
  try {
    return toCondition(JSON.parse(json) as Row)
  } catch {
    return null
  }
}

function toCondition(value: Row): Condition {
  if (Array.isArray(value.items)) {
    const op = value.op === 'ANY' ? 'ANY' : value.op === 'AT_LEAST' ? 'AT_LEAST' : 'ALL'
    return { kind: 'group', op, n: num(value.n), items: (value.items as Row[]).map(toCondition) }
  }
  if (value.node != null) return { kind: 'node', nodeId: String(value.node), beforeAnswer: value.beforeAnswer === true }
  return { kind: 'passed' }
}
