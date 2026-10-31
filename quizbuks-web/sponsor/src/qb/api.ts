import { api, runProcess, runQuery, unwrap } from '@jabiz/client'
import { DATASET, DICTIONARY, PROCESS, TEMPLATE } from './ids'

/*
 * The calls the QuizBuks front ends make, typed by hand after the Java records of backend/quizbuks (processes have no
 * OpenAPI types of their own). The end-to-end tests run every one of them against the server, which keeps the two
 * in step.
 */

/** A taker's own profile (`QbProfile`; a sponsor's carries only the name). */
export interface Profile {
  displayName: string
  countryCode: string | null
  city: string | null
  preferredCurrency: string | null
  revision: number
}

/** `ProfileProcesses.ProfileInput`. */
export interface ProfileInput {
  displayName: string
  countryCode: string
  city?: string | null
  preferredCurrency: string
  /** The revision edited; a newer one on the server is refused (`QB_PROFILE_CHANGED`). */
  baseRevision?: number | null
  /**
   * Registration's second step accepts the takers' terms and the privacy policy in effect (Q7b): the versions the
   * page showed, refused when no longer in effect (`QB_TERMS_OUTDATED`).
   */
  acceptTerms?: boolean
  termsVersionNo?: number | null
  privacyVersionNo?: number | null
}

/** `ProfileProcesses.ProfileOutput`. */
export interface ProfileOutput {
  userId: string
  profileId: string
  revision: number
  editedAt: string
  /** This save completed the profile (registration's second step). */
  created: boolean
}

export type ReviewStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/** A sponsor's own onboarding (`QbSponsor`). */
export interface Sponsor {
  contactName: string
  company: string
  intro: string | null
  /** A file of the policy `qb.sponsor-avatar`. */
  avatar: string | null
  reviewStatus: ReviewStatus
  submittedAt: string | null
  approvedAt: string | null
  rejectReason: string | null
  rejectedAt: string | null
  revision: number
}

/** `SponsorProcesses.ApplyInput` (and, without `acceptTerms`, `ProfileInput`). */
export interface SponsorInput {
  displayName: string
  company: string
  intro?: string | null
  avatar?: string | null
  acceptTerms?: boolean
  baseRevision?: number | null
  /** The versions of the sponsors' terms and the privacy policy the page showed (Q7b). */
  termsVersionNo?: number | null
  privacyVersionNo?: number | null
  /** The prospecting trial that brought the sponsor (Q48e-1): a first application goes to its operator. */
  trialKey?: string | null
}

/** `SponsorProcesses.SponsorOutput`. */
export interface SponsorOutput {
  userId: string
  onboardingId: string
  reviewStatus: ReviewStatus
  revision: number
  /** `PENDING`, `APPROVED` or `NOT_REQUIRED` when the process asked for a review. */
  approval: string | null
  approvalRequestId: string | null
}

/** A country of the dictionary `QbCountry`: its name in each language. */
export interface Country {
  code: string
  name: Record<string, string>
}

/** An item of a static dictionary. */
export interface DictionaryItem {
  code: string
  label: string
}

/** A row of `qb.me.sign-ins`. */
export interface SignInRow {
  loginRecordId: string
  attemptTime: string
  outcome: string
  entry: string | null
  factor: string | null
  clientIp: string | null
  userAgent: string | null
}

function numberOf(value: unknown): number {
  const n = Number(value)
  return Number.isFinite(n) ? n : 0
}

/** The caller's one row of a dataset scoped to them (their profile, their onboarding); null when there is none. */
async function ownRow(dataset: string): Promise<Record<string, unknown> | null> {
  const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
    params: { path: { resourceId: dataset } },
    body: { limit: 1 },
  }))
  const first = (page.items ?? [])[0]
  return first ? ((first.attributes ?? {}) as Record<string, unknown>) : null
}

/** The caller's profile, or null before the first save. */
export async function readMyProfile(): Promise<Profile | null> {
  const row = await ownRow(DATASET.myProfile)
  if (!row) return null
  return {
    displayName: String(row.displayName ?? ''),
    countryCode: (row.countryCode as string | null) ?? null,
    city: (row.city as string | null) ?? null,
    preferredCurrency: (row.preferredCurrency as string | null) ?? null,
    revision: numberOf(row.revision),
  }
}

/** The caller's onboarding as a sponsor, or null before they applied. */
export async function readMySponsor(): Promise<Sponsor | null> {
  const row = await ownRow(DATASET.mySponsor)
  if (!row) return null
  return {
    contactName: String(row.contactName ?? ''),
    company: String(row.company ?? ''),
    intro: (row.intro as string | null) ?? null,
    avatar: row.avatar == null ? null : String(row.avatar),
    reviewStatus: row.reviewStatus as ReviewStatus,
    submittedAt: (row.submittedAt as string | null) ?? null,
    approvedAt: (row.approvedAt as string | null) ?? null,
    rejectReason: (row.rejectReason as string | null) ?? null,
    rejectedAt: (row.rejectedAt as string | null) ?? null,
    revision: numberOf(row.revision),
  }
}

/** The languages a quiz is written in (Q59a), the platform's. */
export type ContentLanguage = 'ja' | 'zh' | 'en'
export const CONTENT_LANGUAGES: readonly ContentLanguage[] = ['ja', 'zh', 'en']

/** The content language of an interface language (`zh-CN` → `zh`); Japanese for another. */
export function contentLanguageOf(language: string | undefined): ContentLanguage {
  const code = (language ?? '').split('-')[0] as ContentLanguage
  return CONTENT_LANGUAGES.includes(code) ? code : 'ja'
}

/**
 * One's own settings (Q57, Q59a): every default when there is no row (the sound on; the quizzes of the interface
 * language only, which `contentLanguages` empty means).
 */
export interface UserSettings {
  soundOn: boolean
  contentLanguages: ContentLanguage[]
}

export const DEFAULT_SETTINGS: UserSettings = { soundOn: true, contentLanguages: [] }

function languagesOf(value: unknown): ContentLanguage[] {
  const listed = Array.isArray(value) ? value.map(String) : typeof value === 'string' ? value.split(',') : []
  return CONTENT_LANGUAGES.filter((language) => listed.includes(language))
}

export async function readMySettings(): Promise<UserSettings> {
  const row = await ownRow(DATASET.mySettings)
  return { soundOn: row?.soundOff !== true, contentLanguages: languagesOf(row?.contentLanguages) }
}

/** Saves the settings given; one left out stays as it is. */
export async function saveMySettings(input: Partial<UserSettings>): Promise<UserSettings> {
  const output = await runProcess<Record<string, unknown>>(PROCESS.settingsSave, {
    soundOn: input.soundOn ?? null, contentLanguages: input.contentLanguages ?? null })
  return { soundOn: output.soundOn !== false, contentLanguages: languagesOf(output.contentLanguages) }
}

/** The languages whose quizzes are shown: the ones switched on, else the interface language. */
export function shownLanguages(settings: UserSettings | undefined, interfaceLanguage: string): ContentLanguage[] {
  const chosen = settings?.contentLanguages ?? []
  return chosen.length > 0 ? chosen : [contentLanguageOf(interfaceLanguage)]
}

/** The most rows a dataset answers at once (the platform's page limit). */
const PAGE = 100

/** Every country, by code, page by page (there are about 250). */
export async function readCountries(): Promise<Country[]> {
  const countries: Country[] = []
  for (let offset = 0; ; offset += PAGE) {
    const page = await unwrap(api.POST('/api/datasets/{resourceId}/query', {
      params: { path: { resourceId: DATASET.countries } },
      body: { offset, limit: PAGE, sorts: [{ field: 'code', asc: true }] },
    }))
    const items = page.items ?? []
    for (const item of items) {
      const row = (item.attributes ?? {}) as Record<string, unknown>
      countries.push({ code: String(row.code), name: (row.name ?? {}) as Record<string, string> })
    }
    if (items.length < PAGE) return countries
  }
}

/** A static dictionary's items in the request's language. */
export async function readDictionary(urn: string): Promise<DictionaryItem[]> {
  const items = await unwrap(api.GET('/api/dictionaries/{urn}', { params: { path: { urn } } }))
  return (items ?? []).filter((item) => item.enabled !== false)
    .map((item) => ({ code: String(item.code), label: String(item.label ?? item.code) }))
}

export const readCurrencies = () => readDictionary(DICTIONARY.currency)

/** The caller's latest sign-ins, newest first. */
export async function readMySignIns(limit = 10): Promise<SignInRow[]> {
  return (await runQuery<SignInRow>(TEMPLATE.mySignIns, { limit })).items
}

export const saveProfile = (input: ProfileInput) =>
  runProcess<ProfileOutput>(PROCESS.profileSave, { ...input })

export const applyAsSponsor = (input: SponsorInput) =>
  runProcess<SponsorOutput>(PROCESS.sponsorApply, { ...input })

/** An approved sponsor's change of the same (no review; the terms are not asked again). */
export const saveSponsorProfile = (input: SponsorInput) =>
  runProcess<SponsorOutput>(PROCESS.sponsorProfileSave, {
    displayName: input.displayName,
    company: input.company,
    intro: input.intro,
    avatar: input.avatar,
    baseRevision: input.baseRevision,
  })
