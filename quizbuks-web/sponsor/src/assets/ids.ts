/**
 * The names the Sponsor app calls on the server for the asset library (F-C1, backend `asset.AssetProcesses`,
 * `AssetConfig`, the templates `qb.sponsor.asset-candidates` and `qb.sponsor.asset-uses`;
 * docs/quizbuks/plans/impl/F-C1.md). Only the Sponsor app uses them, so they live here rather than in the shared
 * `src/qb/ids.ts` (backend/quizbuks/CLAUDE.md section 5).
 */
export const ASSET_PROCESS = {
  save: 'QB_ASSET_SAVE',
  delete: 'QB_ASSET_DELETE',
  collect: 'QB_ASSET_COLLECT',
} as const

export const ASSET_TEMPLATE = {
  /** What of the sponsor's own content is not in the library yet (read when they open the collection only). */
  candidates: 'qb.sponsor.asset-candidates',
  /** Where the sponsor's assets were copied to, with whether the asset changed since. */
  uses: 'qb.sponsor.asset-uses',
  /** F-C2: the latest daily usage snapshot and the free amount (the library's usage card). */
  usage: 'qb.sponsor.usage',
  /** F-C2: each asset's size and how many places use it (sorting by size, the unused ones). */
  stats: 'qb.sponsor.asset-stats',
} as const

/** The sponsor's own assets (read only; writing is the processes'). */
export const ASSET_DATASET = {
  mine: 'urn:jabiz:dataset:me:QbAsset',
} as const

/** Dictionaries, labelled by the server in the request's language. */
export const ASSET_DICTIONARY = {
  kind: 'urn:jabiz:dict:quizbuks:asset-kind',
  usage: 'urn:jabiz:dict:quizbuks:asset-text-usage',
  target: 'urn:jabiz:dict:quizbuks:asset-use-target',
} as const

/** Error codes the pages react to (`asset.AssetCodes`). */
export const ASSET_CODE = {
  /** The asset changed since the page read it: read it again before editing on. */
  changed: 'QB_ASSET_CHANGED',
} as const

/**
 * The limits of an asset, copied from `AssetRules` and `QbParams` (the parameters' defaults). The server checks them
 * all; the page uses them to stop typing early and to say them.
 */
export const ASSET_LIMITS = {
  name: 100,
  description: 500,
  url: 2_000,
  /** An article's body; a text's is its use's (`bodyLength`). */
  body: 20_000,
  intro: 5_000,
  /** A publication's descriptions and a prize card's. */
  prizeDescription: 500,
  maxTags: 10,
  tagLength: 30,
  minVideoSeconds: 1,
  maxVideoSeconds: 7_200,
  maxSendDays: 365,
  /** The most items one collection takes (`AssetRules.MAX_COLLECT`). */
  maxCollect: 200,
} as const
