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
  /** F-S3e: the graph's revision (the editor's `baseRevision`), and the name and description the editor starts from. */
  revision: number
  draftName: string | null
  description: string | null
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
    revision: num(row.revision), draftName: str(row.draftName), description: str(row.description),
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
  /** F-S3f: the daily count of those who answered and have not lit (null: under the minimum group or not counted). */
  answeredUnlit: number | null
  /** The last day counted (null: not counted yet). */
  countedOn: string | null
  /** F-S4b: the platform fee Kudos lighting the node grants (null: none). */
  grantAmount: number | null
}

/** A version node by node: the platform's template, or an operator's (only the maps the operator looks after). */
export async function readTreeVersion(operator: boolean, versionId: string): Promise<VersionNode[]> {
  const page = await runQuery<Row>(operator ? TEMPLATE.operatorTreeVersion : TEMPLATE.treeVersion, { params: { versionId }, limit: 1000 })
  return page.items.map((row) => ({ nodeId: String(row.nodeId), change: String(row.change ?? ''), name: str(row.name),
    description: str(row.description),
    condition: row.condition == null ? null : typeof row.condition === 'string' ? row.condition : JSON.stringify(row.condition), runningBound: num(row.runningBound),
    answeredUnlit: row.answeredUnlit == null ? null : num(row.answeredUnlit), countedOn: str(row.countedOn),
    grantAmount: row.grantAmount == null ? null : num(row.grantAmount) }))
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

export async function readTreeLit(operator: boolean, treeId: string, offset: number,
  limit: number): Promise<Page<LitRecord>> {
  const page = await runQuery<Row>(operator ? TEMPLATE.operatorTreeLit : TEMPLATE.treeLit, { params: { treeId }, offset, limit, count: true })
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

// ---- F-S3e: editing a platform template or an operator's copy (`tree.TreeProcesses`) -----------------------------

/** A problem the server finds in a draft (`TreeProcesses.ProblemView`), on a node or (null) the graph. */
export interface TreeProblem {
  nodeId: string | null
  code: string
  params: Record<string, unknown>
}

/** `TreeProcesses.TreeOutput`. */
export interface TreeOutput {
  treeId: string
  versionId: string
  versionNo: number
  status: string
  revision: number
  nodeIds: Record<string, string> | null
  problems: TreeProblem[] | null
  approval: string | null
  approvalRequestId: string | null
}

/** A new platform template (`qb.tree.platform.write`), lit by Takers or by Sponsors. */
export const createTemplate = (subjectKind: 'TAKER' | 'SPONSOR', name: Record<string, string>) =>
  runProcess<TreeOutput>(PROCESS.treeCreate, { ownerKind: 'PLATFORM', subjectKind, name })

/** The caller's operator's copy of a template lit by Sponsors (one per template). */
export const copyTemplate = (templateTreeId: string) =>
  runProcess<TreeOutput>(PROCESS.treeCopy, { templateTreeId })

/** Saves the whole draft (`TreeProcesses.SaveInput`). */
export const saveTreeDraft = (input: Record<string, unknown>) => runProcess<TreeOutput>(PROCESS.treeSave, input)

export const submitTree = (treeId: string, baseRevision: number) =>
  runProcess<TreeOutput>(PROCESS.treeSubmit, { treeId, baseRevision })

export const withdrawTree = (treeId: string, baseRevision: number) =>
  runProcess<TreeOutput>(PROCESS.treeWithdraw, { treeId, baseRevision })

/** Takes the template's nodes into an operator's copy, node by node (`QB_TREE_ADOPT`). */
export const adoptTemplate = (treeId: string, baseRevision: number, nodeIds: string[]) =>
  runProcess<TreeOutput>(PROCESS.treeAdopt, { treeId, baseRevision, nodeIds })

export type TemplateChange = 'ADDED' | 'CHANGED' | 'SAME' | 'NOT_IN_TEMPLATE'

/** A node of an operator's copy against its template's version in effect (`qb.operator.tree-template-diff`). */
export interface TemplateDiffRow {
  nodeId: string
  change: TemplateChange | string
  name: string | null
  copyName: string | null
  condition: string | null
  templateVersionNo: number | null
  takenVersionNo: number | null
}

export async function readTemplateDiff(treeId: string): Promise<TemplateDiffRow[]> {
  const page = await runQuery<Row>(TEMPLATE.operatorTreeTemplateDiff, { params: { treeId }, limit: 1000 })
  return page.items.map((row) => ({ nodeId: String(row.nodeId), change: String(row.change ?? ''), name: str(row.name),
    copyName: str(row.copyName), condition: row.condition == null ? null : String(row.condition),
    templateVersionNo: row.templateVersionNo == null ? null : num(row.templateVersionNo),
    takenVersionNo: row.takenVersionNo == null ? null : num(row.takenVersionNo) }))
}

/** A template an operator may copy (`qb.operator.tree-templates`): lit by Sponsors, in effect. */
export interface TemplateRow {
  treeId: string
  name: string | null
  versionNo: number | null
  copyTreeId: string | null
}

export async function readCopyableTemplates(): Promise<TemplateRow[]> {
  const page = await runQuery<Row>(TEMPLATE.operatorTreeTemplates, { limit: 200 })
  return page.items.map((row) => ({ treeId: String(row.treeId), name: str(row.name),
    versionNo: row.versionNo == null ? null : num(row.versionNo), copyTreeId: str(row.copyTreeId) }))
}

/** One person's lighting of one map, read by support and recorded (`QB_SUPPORT_TREE_STATE`). */
export interface SupportLit {
  litId: string
  litAt: string | null
  revokedAt: string | null
  revokeReason: string | null
}

export interface SupportNode {
  nodeId: string
  /** The node's names by language (as a map or its JSON text). */
  name: unknown
  state: 'LIT' | 'OPEN' | 'UNMET' | string
  unmet: string[]
  lits: SupportLit[]
}

export interface SupportState {
  treeId: string
  ownerKind: string
  ownerId: string | null
  nodes: SupportNode[]
}

export const readSupportTreeState = (userId: string, treeId: string) =>
  runProcess<SupportState>(PROCESS.supportTreeState, { userId, treeId })
