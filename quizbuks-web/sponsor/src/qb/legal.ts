import { runProcess, runQuery } from '@jabiz/client'
import { LEGAL } from './ids'

/*
 * Terms, privacy and help (docs/quizbuks/plans/Q7-messages-legal.md, Q7b): the public templates anybody reads
 * (`GET /api/public/queries/{id}`, no token: the pages work before signing in), the caller's standing with the texts
 * (`qb.me.consents`) and accepting them (`QB_CONSENT_ACCEPT`). Typed by hand after the templates' results.
 */

/** A text in several languages, as the server keeps it (`{en: …, ja: …}`). */
export type I18nText = Record<string, string>

export type LegalKind = 'TERMS_APP' | 'TERMS_SPONSOR' | 'PRIVACY' | 'COMMERCE_NOTICE'

/** The pages' addresses of the texts (`/legal/<slug>`), and back. */
export const LEGAL_SLUGS: Record<string, LegalKind> = {
  terms: 'TERMS_APP',
  'sponsor-terms': 'TERMS_SPONSOR',
  privacy: 'PRIVACY',
  commerce: 'COMMERCE_NOTICE',
}

export function legalPath(kind: LegalKind): string {
  const slug = Object.entries(LEGAL_SLUGS).find(([, k]) => k === kind)?.[0] ?? 'terms'
  return `/legal/${slug}`
}

/** The kind of a page's path (`/legal/privacy`), or null for any other path. */
export function legalKindOf(path: string): LegalKind | null {
  const match = /^\/legal\/([a-z-]+)$/.exec(path)
  return match ? LEGAL_SLUGS[match[1]] ?? null : null
}

/** A row of `qb.public.legal`: the version in effect (if any) and the one announced for later (if any). */
export interface LegalCurrent {
  kind: LegalKind
  versionNo: number | null
  title: I18nText | null
  body: I18nText | null
  changeSummary: I18nText | null
  requiresConsent: boolean | null
  effectiveAt: string | null
  upcomingVersionNo: number | null
  upcomingEffectiveAt: string | null
}

/** A row of `qb.public.legal-version`. */
export interface LegalVersion {
  kind: LegalKind
  versionNo: number
  title: I18nText
  body: I18nText
  changeSummary: I18nText | null
  requiresConsent: boolean
  effectiveAt: string
}

/** A row of `qb.public.legal-history`. */
export interface LegalHistoryRow {
  versionNo: number
  title: I18nText
  changeSummary: I18nText | null
  requiresConsent: boolean
  effectiveAt: string
}

export type FaqAudience = 'APP' | 'SPONSOR'

/** A row of `qb.public.faq`. */
export interface FaqEntry {
  faqId: string
  category: string
  question: I18nText
  answer: I18nText
  seq: number
}

/** The help centre's categories in their order (the dictionary `faq-category`), by audience. */
export const FAQ_CATEGORIES: Record<FaqAudience, string[]> = {
  APP: ['ACCOUNT', 'QUIZ_RULES', 'REWARDS', 'OTHER'],
  SPONSOR: ['ONBOARDING', 'PUBLISHING', 'BILLING', 'ACCOUNT', 'OTHER'],
}

/** A row of `qb.me.consents`: the caller's standing with one kind of text. */
export interface ConsentState {
  kind: LegalKind
  versionId: string
  versionNo: number
  title: I18nText
  changeSummary: I18nText | null
  requiresConsent: boolean
  effectiveAt: string
  toAccept: number
  accepted: number | null
  mustAccept: boolean
}

/** The time the pages read the texts at: now, to the minute (the public answers are cached and tagged by it). */
export function minuteNow(now: number = Date.now()): string {
  return new Date(Math.floor(now / 60_000) * 60_000).toISOString()
}

/** A public template's rows; anybody may ask (no token is sent). */
export async function publicQuery<Row>(id: string, params: Record<string, string | number | undefined>,
  limit = 100): Promise<Row[]> {
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(params)) {
    if (value !== undefined && value !== '') query.append(`p.${name}`, String(value))
  }
  query.set('limit', String(limit))
  query.set('count', 'false')
  const response = await fetch(`/api/public/queries/${encodeURIComponent(id)}?${query}`, {
    headers: { Accept: 'application/json' },
  })
  if (!response.ok) throw new Error(`HTTP ${response.status}`)
  const page = (await response.json()) as { items?: Row[] }
  return page.items ?? []
}

export async function readLegal(kind: LegalKind, at: string = minuteNow()): Promise<LegalCurrent | null> {
  return (await publicQuery<LegalCurrent>(LEGAL.publicLegal, { kind, at }))[0] ?? null
}

export async function readLegalVersion(kind: LegalKind, versionNo: number): Promise<LegalVersion | null> {
  return (await publicQuery<LegalVersion>(LEGAL.publicLegalVersion, { kind, versionNo }))[0] ?? null
}

export const readLegalHistory = (kind: LegalKind) =>
  publicQuery<LegalHistoryRow>(LEGAL.publicLegalHistory, { kind })

export const readFaq = (audience: FaqAudience, q?: string) =>
  publicQuery<FaqEntry>(LEGAL.publicFaq, { audience, q: q?.trim() || undefined })

/** The caller's standing with the kinds their front end asks for; a kind without a version in effect has no row. */
export async function readMyConsents(kinds: readonly LegalKind[], at: string = minuteNow()):
  Promise<ConsentState[]> {
  return (await runQuery<ConsentState>(LEGAL.myConsents, { params: { kinds: [...kinds], at } })).items
}

/** The support address signed-in users write to, or null when none is set. */
export async function readSupportEmail(): Promise<string | null> {
  const row = (await runQuery<{ email: string | null }>(LEGAL.mySupport)).items[0]
  return row?.email ?? null
}

/** Accepts the versions in effect (`QB_CONSENT_ACCEPT`); returns the kinds recorded now. */
export async function acceptConsents(items: { kind: LegalKind; versionNo: number }[]): Promise<string[]> {
  const output = await runProcess<{ recorded: string[] }>(LEGAL.accept, { items })
  return output?.recorded ?? []
}

/**
 * A text in the reader's language, else English, else Japanese, else any; `lang` is set when another language is
 * shown (for the element's `lang` attribute). Empty when the text has none.
 */
export function pickText(text: I18nText | null | undefined, language: string):
  { text: string; lang?: string } {
  if (!text) return { text: '' }
  const base = language.split('-')[0]
  if (text[base]?.trim()) return { text: text[base] }
  for (const fallback of ['en', 'ja', 'zh']) {
    if (fallback !== base && text[fallback]?.trim()) return { text: text[fallback], lang: fallback }
  }
  return { text: '' }
}
