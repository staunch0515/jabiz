import { api, runProcess, runQuery, unwrap } from '@jabiz/client'
import { useQuery } from '@tanstack/react-query'
import { parseStoredTranslations, type TextsByLanguage } from '../content/TextTranslationsFields'
import { readDictionary, type ContentLanguage } from '../qb/api'
import type { DigitalDelivery } from '../publishing/api'
import { ASSET_DATASET, ASSET_DICTIONARY, ASSET_LIMITS, ASSET_PROCESS, ASSET_TEMPLATE } from './ids'

/*
 * The sponsor's asset library (F-C1), typed by hand after `asset.AssetProcesses`'s records, the `me:QbAsset` dataset
 * and the two templates. The end-to-end test `assets.spec.ts` runs these against the server.
 */

export const ASSETS_PATH = '/assets'
export const ASSETS_QUERY = ['qb', 'my-assets'] as const
export const assetUsesQuery = (assetId: string) => ['qb', 'asset-uses', assetId] as const

export type AssetKind = 'IMAGE' | 'PDF' | 'AUDIO' | 'VIDEO_LINK' | 'LINK' | 'ARTICLE' | 'TEXT' | 'PRIZE'
export const ASSET_KINDS: readonly AssetKind[] = ['IMAGE', 'PDF', 'AUDIO', 'VIDEO_LINK', 'LINK', 'ARTICLE', 'TEXT',
  'PRIZE']

/** What a text asset is for (`AssetRules.USAGES`): the field it is copied into decides its length. */
export type TextUsage = 'QUIZ_INTRO' | 'REDEEM_DESCRIPTION' | 'DIGITAL_DESCRIPTION' | 'REFERRAL_DESCRIPTION'
export const TEXT_USAGES: readonly TextUsage[] = ['QUIZ_INTRO', 'REDEEM_DESCRIPTION', 'DIGITAL_DESCRIPTION',
  'REFERRAL_DESCRIPTION']

/** The methods a prize card can be (`AssetRules.PRIZE_METHODS`): those a sponsor hands over themselves. */
export type PrizeMethod = 'OFFLINE_REDEEM' | 'DIGITAL_PRIZE' | 'SPONSOR_CASH'
export const PRIZE_METHODS: readonly PrizeMethod[] = ['OFFLINE_REDEEM', 'DIGITAL_PRIZE', 'SPONSOR_CASH']
export const DIGITAL_DELIVERIES: readonly DigitalDelivery[] = ['POOL', 'SHARED', 'SPONSOR_SENT']

/**
 * The page's tabs (the spec's "全部 / 素材 / 文字 / 奖品 / 商品 / 认证"). The products (Q60b) and the certifications
 * (Q88a) moved in as they were (the requester, 2026-11-07): their own pages, data and actions; they are not assets, so
 * "all" lists the assets only.
 */
export type AssetTab = 'all' | 'media' | 'text' | 'prize' | 'products' | 'certifications'
export const ASSET_TABS: readonly AssetTab[] = ['all', 'media', 'text', 'prize', 'products', 'certifications']
/** The tabs of the library's own assets. */
export const isAssetTab = (tab: AssetTab) => tab !== 'products' && tab !== 'certifications'
export const TAB_KINDS: Record<AssetTab, readonly AssetKind[]> = {
  all: ASSET_KINDS,
  media: ['IMAGE', 'PDF', 'AUDIO', 'VIDEO_LINK', 'LINK', 'ARTICLE'],
  text: ['TEXT'],
  prize: ['PRIZE'],
  products: [],
  certifications: [],
}

/** The content fields an asset may have; each kind fills its own (`AssetKind.contentFields`). */
export type ContentField = 'image' | 'pdf' | 'audio' | 'url' | 'durationSec' | 'body' | 'usage' | 'prizeMethod'
  | 'prizeDescription' | 'digitalDelivery' | 'digitalSendDays'
export const CONTENT_FIELDS: Record<AssetKind, readonly ContentField[]> = {
  IMAGE: ['image'],
  PDF: ['pdf'],
  AUDIO: ['audio'],
  VIDEO_LINK: ['url', 'durationSec'],
  LINK: ['url'],
  ARTICLE: ['body'],
  TEXT: ['body', 'usage'],
  PRIZE: ['prizeMethod', 'prizeDescription', 'digitalDelivery', 'digitalSendDays'],
}

/** An asset's content (the fields every kind's content is among). */
export interface AssetContent {
  image: string | null
  pdf: string | null
  audio: string | null
  url: string | null
  durationSec: number | null
  body: string | null
  usage: TextUsage | null
  prizeMethod: PrizeMethod | null
  prizeDescription: string | null
  digitalDelivery: DigitalDelivery | null
  digitalSendDays: number | null
}

/** One of the sponsor's assets, as `me:QbAsset` gives it. */
export interface Asset extends AssetContent {
  assetId: string
  kind: AssetKind
  name: string
  description: string | null
  language: ContentLanguage
  tags: string[]
  /** The texts in other languages, and the hashes of the base texts they translate (Q73c). */
  translations: TextsByLanguage
  translationHashes: TextsByLanguage
  revision: number
  editedAt: string
}

/** `AssetProcesses.SaveInput`: only the kind's own content fields are sent (the others must be empty). */
export interface AssetInput extends Partial<AssetContent> {
  assetId: string | null
  kind: AssetKind
  name: string
  description: string | null
  language: ContentLanguage
  tags: string[]
  /** The texts in other languages: {language: {field: text}}; none keeps those stored. */
  translations?: TextsByLanguage
  confirmed?: string[]
  baseRevision: number | null
}

export const EMPTY_CONTENT: AssetContent = { image: null, pdf: null, audio: null, url: null, durationSec: null,
  body: null, usage: null, prizeMethod: null, prizeDescription: null, digitalDelivery: null, digitalSendDays: null }

type Row = Record<string, unknown>
const text = (value: unknown) => (value == null || value === '' ? null : String(value))
const number = (value: unknown) => (value == null || value === '' ? null : Number(value))

/** The stored tag list (JSON text); none for anything else. */
export function tagsOf(value: unknown): string[] {
  if (Array.isArray(value)) return value.map(String)
  if (typeof value !== 'string' || value === '') return []
  try {
    const parsed: unknown = JSON.parse(value)
    return Array.isArray(parsed) ? parsed.map(String) : []
  } catch {
    return []
  }
}

export function toAsset(row: Row): Asset {
  const stored = parseStoredTranslations(row.translations)
  return {
    assetId: String(row.assetId ?? ''),
    kind: String(row.kind) as AssetKind,
    name: String(row.name ?? ''),
    description: text(row.description),
    language: (text(row.language) ?? 'ja') as ContentLanguage,
    tags: tagsOf(row.tags),
    translations: stored.texts,
    translationHashes: stored.hashes,
    image: text(row.image),
    pdf: text(row.pdf),
    audio: text(row.audio),
    url: text(row.url),
    durationSec: number(row.durationSec),
    body: text(row.body),
    usage: text(row.usage) as TextUsage | null,
    prizeMethod: text(row.prizeMethod) as PrizeMethod | null,
    prizeDescription: text(row.prizeDescription),
    digitalDelivery: text(row.digitalDelivery) as DigitalDelivery | null,
    digitalSendDays: number(row.digitalSendDays),
    revision: Number(row.revision) || 0,
    editedAt: String(row.editedAt ?? ''),
  }
}

const PAGE = 200

/** All the caller's assets, the latest edited first (a page at a time; at most `qb.assets.max-count`). */
export async function readMyAssets(): Promise<Asset[]> {
  const rows: Row[] = []
  for (let offset = 0; ; offset += PAGE) {
    const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: ASSET_DATASET.mine } },
      body: { limit: PAGE, offset, sorts: [{ field: 'editedAt', asc: false }] },
    }))
    const items = (page.items ?? []).map((item) => (item.attributes ?? {}) as Row)
    rows.push(...items)
    if (items.length < PAGE) break
  }
  return rows.map(toAsset)
}

/** Only the kind's own content: what the server takes (the other fields must be empty). */
export function contentOf(kind: AssetKind, content: Partial<AssetContent>): Partial<AssetContent> {
  return Object.fromEntries(CONTENT_FIELDS[kind].map((field) => [field, content[field] ?? null]))
}

export const saveAsset = (input: AssetInput) => {
  const { kind, assetId, name, description, language, tags, translations, confirmed, baseRevision } = input
  return runProcess<{ assetId: string; revision: number }>(ASSET_PROCESS.save, { assetId, kind, name, description,
    language, tags, ...(translations ? { translations } : {}), confirmed: confirmed ?? [], baseRevision,
    ...contentOf(kind, input) })
}

export const deleteAsset = (assetId: string, baseRevision: number) =>
  runProcess<{ assetId: string; uses: number }>(ASSET_PROCESS.delete, { assetId, baseRevision })

/** The places an asset was copied to (`QbAssets.TARGETS`). */
export type UseTarget = 'MATERIAL' | 'QUESTION_IMAGE' | 'OPTION_IMAGE' | 'QUIZ_COVER' | 'QUIZ_INTRO'
  | 'PUBLICATION_COVER' | 'PUBLICATION_REDEEM' | 'PUBLICATION_DIGITAL' | 'PUBLICATION_REFERRAL' | 'PUBLICATION_PRIZE'

/** A row of `qb.sponsor.asset-uses`. */
export interface AssetUse {
  useId: string
  assetId: string
  targetKind: UseTarget
  targetId: string
  quizId: string | null
  publicationId: string | null
  /** The quiz's title, the material's or the publication's name; none for a removed quiz. */
  targetName: string | null
  /** The asset changed since it was copied here ("older content"). */
  stale: boolean
  usedAt: string
}

export async function readAssetUses(assetId: string): Promise<AssetUse[]> {
  const page = await runQuery<Row>(ASSET_TEMPLATE.uses, {
    filters: [{ field: 'assetId', op: 'eq', value: assetId }], limit: 200 })
  return page.items.map((row) => ({
    useId: String(row.useId),
    assetId: String(row.assetId),
    targetKind: String(row.targetKind) as UseTarget,
    targetId: String(row.targetId ?? ''),
    quizId: text(row.quizId),
    publicationId: text(row.publicationId),
    targetName: text(row.targetName),
    stale: row.stale === true || row.stale === 'true',
    usedAt: String(row.usedAt ?? ''),
  }))
}

/** A row of `qb.sponsor.asset-candidates`: content of the sponsor's not yet in the library. */
export interface Candidate extends AssetContent {
  candidateKey: string
  kind: AssetKind
  sourceKind: UseTarget
  quizId: string | null
  publicationId: string | null
  name: string | null
  description: string | null
  language: ContentLanguage
}

export async function readCandidates(): Promise<Candidate[]> {
  const page = await runQuery<Row>(ASSET_TEMPLATE.candidates, { limit: ASSET_LIMITS.maxCollect })
  return page.items.map((row) => ({
    ...toAsset(row),
    candidateKey: String(row.candidateKey),
    sourceKind: String(row.sourceKind) as UseTarget,
    quizId: text(row.quizId),
    publicationId: text(row.publicationId),
    name: text(row.name),
  }))
}

/** `AssetProcesses.CollectOutput`. */
export interface CollectOutput {
  created: { index: number; assetId: string }[]
  skipped: { index: number; assetId: string | null; sameAs: number | null }[]
}

/** Collects the candidates as new assets, each named by its place (or `fallbackName` when it has no name). */
export function collectAssets(candidates: Candidate[], fallbackName: (candidate: Candidate) => string) {
  return runProcess<CollectOutput>(ASSET_PROCESS.collect, {
    items: candidates.map((candidate) => ({
      kind: candidate.kind,
      name: (candidate.name ?? '').trim().slice(0, ASSET_LIMITS.name) || fallbackName(candidate),
      description: candidate.description ? candidate.description.slice(0, ASSET_LIMITS.description) : null,
      language: candidate.language,
      tags: [],
      ...contentOf(candidate.kind, candidate),
    })),
  })
}

/** A dictionary's labels by code (the code itself until the server answered). */
function useLabels(key: string, urn: string) {
  const items = useQuery({ queryKey: ['qb', 'dict', key], queryFn: () => readDictionary(urn), staleTime: Infinity })
  return (code: string) => items.data?.find((item) => item.code === code)?.label ?? code
}

export const useAssetKindLabel = () => useLabels('asset-kind', ASSET_DICTIONARY.kind)
export const useTextUsageLabel = () => useLabels('asset-text-usage', ASSET_DICTIONARY.usage)
export const useUseTargetLabel = () => useLabels('asset-use-target', ASSET_DICTIONARY.target)

/** The most characters a text's body has for its use; an article's otherwise (`AssetRules.bodyLength`). */
export function bodyLength(kind: AssetKind, usage: TextUsage | null): number {
  if (kind !== 'TEXT') return ASSET_LIMITS.body
  return usage === 'QUIZ_INTRO' ? ASSET_LIMITS.intro : ASSET_LIMITS.prizeDescription
}

/** Whether the asset matches a search: its name, description or a tag contains the words (any case). */
export function matches(asset: Pick<Asset, 'name' | 'description' | 'tags'>, search: string): boolean {
  const q = search.trim().toLowerCase()
  if (q === '') return true
  return [asset.name, asset.description ?? '', ...asset.tags].some((value) => value.toLowerCase().includes(q))
}
