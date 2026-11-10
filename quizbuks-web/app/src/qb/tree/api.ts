import { runProcess, runQuery } from '@jabiz/client'
import { publicQuery, type I18nText } from '../legal'
import { PLAY, TREE } from '../ids'
import { graphOf, type Graph } from './states'
import type { Startable, UnlitCount } from './KnowledgeMap'

/*
 * The knowledge graphs' reads (F-S3, docs/quizbuks/plans/impl/F-S3.md §2, §4), typed by hand after the templates
 * `qb.app.trees`, `qb.app.tree`, `qb.app.feed` (its `tree` parameter), `qb.public.sponsor-page-tree` and the Sponsor's
 * `qb.sponsor.trees` / `tree-versions` / `tree`. The server decides who sees what; the pages only show it.
 */

type Row = Record<string, unknown>
const num = (value: unknown) => (value == null ? 0 : Number(value))
const str = (value: unknown) => (value == null ? null : String(value))

function json<T>(value: unknown, fallback: T): T {
  if (value == null) return fallback
  if (typeof value !== 'string') return value as T
  try {
    return JSON.parse(value) as T
  } catch {
    return fallback
  }
}

/** A graph of "my maps" (`qb.app.trees`). */
export interface MapSummary {
  treeId: string
  ownerKind: 'PLATFORM' | 'SPONSOR'
  /** The Sponsor's company (an approved onboarding's); null for the platform's. */
  sponsorCompany: string | null
  name: I18nText
  description: I18nText | null
  nodes: number
  litNodes: number
}

function summaryOf(row: Row): MapSummary {
  return {
    treeId: String(row.treeId),
    ownerKind: row.ownerKind === 'PLATFORM' ? 'PLATFORM' : 'SPONSOR',
    sponsorCompany: str(row.sponsorCompany),
    name: json<I18nText>(row.name, {}),
    description: json<I18nText | null>(row.description, null),
    nodes: num(row.nodes),
    litNodes: num(row.litNodes),
  }
}

/** The caller's maps: the platform's and the Sponsors' they answered or lit something in; the platform's first. */
export async function readMyMaps(): Promise<MapSummary[]> {
  return (await runQuery<Row>(TREE.myTrees, { params: {}, limit: 100 })).items.map(summaryOf)
}

/** The maps in effect of the Sponsor whose published page has the handle (for the signed-in caller). */
export async function readPageMaps(handle: string): Promise<MapSummary[]> {
  return (await runQuery<Row>(TREE.myTrees, { params: { handle: handle.toLowerCase() }, limit: 10 })).items
    .map(summaryOf)
}

/** One map as the caller sees it; null when it is not one they may see. */
export async function readMap(treeId: string): Promise<Graph | null> {
  return graphOf((await runQuery<Row>(TREE.tree, { params: { treeId }, limit: 500 })).items)
}

/** The publications bound to the map's nodes the caller can start now, by node (`qb.app.feed` with `tree`). */
export async function readStartable(request: { treeId: string; at: string; country: string | null;
  mainlandChina?: boolean }): Promise<Map<string, Startable[]>> {
  const rows = (await runQuery<Row>(PLAY.feed, { params: { at: request.at, country: request.country,
    tree: request.treeId, mainlandChina: request.mainlandChina ?? false }, limit: 200 })).items
  const byNode = new Map<string, Startable[]>()
  for (const row of rows) {
    const node = str(row.treeNodeId)
    if (!node) continue
    byNode.set(node, [...(byNode.get(node) ?? []), { publicationId: String(row.publicationId), name: String(row.name) }])
  }
  return byNode
}

/** The map of a published Sponsor's page as anybody reads it (no lighting); null for none. */
export async function readPublicPageMap(handle: string): Promise<Graph | null> {
  return graphOf(await publicQuery<Row>(TREE.publicPageTree, { handle: handle.toLowerCase() }, 500))
}

// ---- the Sponsor's own (qb.sponsor.trees / tree-versions / tree) -------------------------------------------------

export type VersionStatus = 'DRAFT' | 'REVIEW' | 'ACTIVE' | 'SUPERSEDED' | 'REJECTED'

/** One of the Sponsor's graphs: the version in effect and the open one (a draft, in review or sent back). */
export interface SponsorMap {
  treeId: string
  status: 'ACTIVE' | 'RETIRED' | string
  name: I18nText
  description: I18nText | null
  currentVersionId: string | null
  currentVersionNo: number | null
  openVersionId: string | null
  openVersionNo: number | null
  openStatus: VersionStatus | null
  rejectReason: string | null
  submittedAt: string | null
  editedAt: string | null
  nodes: number
  runningBound: number
  /** The graph's revision (`baseRevision` of the editor's saves). */
  revision: number
}

export async function readSponsorMaps(): Promise<SponsorMap[]> {
  return (await runQuery<Row>(TREE.sponsorTrees, { params: {}, limit: 50 })).items.map((row) => ({
    treeId: String(row.treeId),
    status: String(row.status),
    name: json<I18nText>(row.name, {}),
    description: json<I18nText | null>(row.description, null),
    currentVersionId: str(row.currentVersionId),
    currentVersionNo: row.currentVersionNo == null ? null : num(row.currentVersionNo),
    openVersionId: str(row.openVersionId),
    openVersionNo: row.openVersionNo == null ? null : num(row.openVersionNo),
    openStatus: (str(row.openStatus) as VersionStatus | null),
    rejectReason: str(row.rejectReason),
    submittedAt: str(row.submittedAt),
    editedAt: str(row.editedAt),
    nodes: num(row.nodes),
    runningBound: num(row.runningBound),
    revision: num(row.revision),
  }))
}

/** A version's nodes as the Sponsor reads them, with the lit counts (null under the minimum group). */
export interface SponsorVersion {
  graph: Graph
  litCounts: Map<string, number | null>
  /** F-S3f: the daily count of those who answered and have not lit, for the nodes counted (null: under the minimum). */
  unlitCounts: Map<string, UnlitCount>
  runningBound: Map<string, number>
}

export async function readSponsorVersion(map: Pick<SponsorMap, 'treeId' | 'name' | 'description'>,
  versionId: string): Promise<SponsorVersion | null> {
  const rows = (await runQuery<Row>(TREE.sponsorTree, { params: { versionId }, limit: 500 })).items
  const graph = graphOf(rows.map((row) => ({ ...row, treeId: map.treeId, ownerKind: 'SPONSOR',
    treeName: JSON.stringify(map.name), treeDescription: map.description ? JSON.stringify(map.description) : null })))
  if (!graph) return null
  return {
    graph,
    litCounts: new Map(rows.map((row) => [String(row.nodeId), row.litCount == null ? null : num(row.litCount)])),
    unlitCounts: new Map(rows.filter((row) => row.countedOn != null).map((row) => [String(row.nodeId),
      { count: row.answeredUnlit == null ? null : num(row.answeredUnlit), countedOn: String(row.countedOn) }])),
    runningBound: new Map(rows.map((row) => [String(row.nodeId), num(row.runningBound)])),
  }
}

/** A node the publication wizard may bind a publication to (`qb.sponsor.tree-bindable-nodes`). */
export interface BindableNode {
  treeId: string
  ownerKind: 'PLATFORM' | 'SPONSOR'
  treeName: I18nText
  nodeId: string
  name: I18nText
}

/**
 * The nodes the caller may bind a publication to, as `publishing.PublicationNodes` allows: their own graph's in effect
 * asking for a pass, and, for an operator's direct Sponsor, the platform's graphs Takers light.
 */
export async function readBindableNodes(): Promise<BindableNode[]> {
  return (await runQuery<Row>(TREE.sponsorBindable, { params: {}, limit: 500 })).items.map((row) => ({
    treeId: String(row.treeId),
    ownerKind: row.ownerKind === 'PLATFORM' ? 'PLATFORM' : 'SPONSOR',
    treeName: json<I18nText>(row.treeName, {}),
    nodeId: String(row.nodeId),
    name: json<I18nText>(row.name, {}),
  }))
}

/** A version of the Sponsor's graph (`qb.sponsor.tree-versions`), newest first. */
export interface SponsorVersionRow {
  versionId: string
  versionNo: number
  status: VersionStatus
  name: I18nText
  submittedAt: string | null
  decidedAt: string | null
  rejectReason: string | null
  editedAt: string | null
}

export async function readSponsorVersions(treeId: string): Promise<SponsorVersionRow[]> {
  return (await runQuery<Row>(TREE.sponsorVersions, { params: { treeId }, limit: 100 })).items.map((row) => ({
    versionId: String(row.versionId),
    versionNo: num(row.versionNo),
    status: String(row.status) as VersionStatus,
    name: json<I18nText>(row.name, {}),
    submittedAt: str(row.submittedAt),
    decidedAt: str(row.decidedAt),
    rejectReason: str(row.rejectReason),
    editedAt: str(row.editedAt),
  }))
}

/** A problem the server finds in a draft (`TreeProcesses.ProblemView`), on a node or (null) the graph. */
export interface TreeProblem {
  nodeId: string | null
  code: string
  params: Record<string, unknown>
}

/** The output of the editor's processes (`TreeProcesses.TreeOutput`). */
export interface TreeOutput {
  treeId: string
  versionId: string
  versionNo: number
  status: VersionStatus
  revision: number
  /** New nodes' ids by the save's keys. */
  nodeIds: Record<string, string> | null
  problems: TreeProblem[] | null
  approval: string | null
  approvalRequestId: string | null
}

export function createSponsorMap(name: I18nText): Promise<TreeOutput> {
  return runProcess<TreeOutput>(TREE.create, { ownerKind: 'SPONSOR', name })
}

export function saveMapDraft(input: Record<string, unknown>): Promise<TreeOutput> {
  return runProcess<TreeOutput>(TREE.save, input)
}

export function submitMap(treeId: string, baseRevision: number | null): Promise<TreeOutput> {
  return runProcess<TreeOutput>(TREE.submit, { treeId, baseRevision })
}

export function withdrawMap(treeId: string, baseRevision: number | null): Promise<TreeOutput> {
  return runProcess<TreeOutput>(TREE.withdraw, { treeId, baseRevision })
}
