import { api, runProcess, runQuery, unwrap } from '@jabiz/client'
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
  /** Withdrawn before it took effect (it never did). */
  withdrawn: boolean
}

/** A row of `qb.public.legal-history`. */
export interface LegalHistoryRow {
  versionNo: number
  title: I18nText
  changeSummary: I18nText | null
  requiresConsent: boolean
  effectiveAt: string
  /** Withdrawn before it took effect (it never did). */
  withdrawn: boolean
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

/*
 * The server's clock as the pages know it (review M2): which version is in effect, and whether one must be accepted,
 * is the server's to say at its own time, not the device's (whose clock may be minutes or days off). The answers of
 * `qb.me.consents` carry a `Date` header (whole seconds): from it the pages learn how far the device is off. Only
 * that template's answers teach it: the public ones may come from a cache, with the date of when they were made.
 */
let clockOffset = 0
/** What the header cannot tell apart: its whole seconds and a little. */
const CLOCK_TOLERANCE_MS = 2_000

/** Now (epoch milliseconds) by the server's clock, as far as the pages know it. */
export function serverNow(now: number = Date.now()): number {
  return now + clockOffset
}

/**
 * Learns the server's clock from an answer's `Date` header, sent and received at those device times; whether the
 * offset changed. Within the header's precision and half the round trip the clocks agree.
 */
export function learnServerClock(date: string | null, sentAt: number, receivedAt: number): boolean {
  const server = date ? Date.parse(date) : Number.NaN
  if (Number.isNaN(server)) return false
  // The header is truncated to the second: the server answered within [server, server + 1 s).
  const estimate = server + 500 - (sentAt + receivedAt) / 2
  const margin = CLOCK_TOLERANCE_MS + (receivedAt - sentAt) / 2
  const next = Math.abs(estimate) <= margin ? 0 : Math.round(estimate)
  if (Math.abs(next - clockOffset) <= margin) return false
  clockOffset = next
  return true
}

/** Forgets the server's clock (tests). */
export function resetServerClock() {
  clockOffset = 0
}

/**
 * The time the pages read the texts at: the server's now, to the minute (the public answers are cached and tagged
 * by it).
 */
export function minuteNow(now: number = serverNow()): string {
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

/**
 * The caller's standing with the kinds their front end asks for; a kind without a version in effect has no row. At
 * the server's time, this very second, not to the minute (the answer is the caller's own, not cached): a version that
 * took effect seconds ago is the one the server asks for. When the answer shows the device's clock is off by more
 * than the page knew, it is read again at the server's time (review M2).
 */
export async function readMyConsents(kinds: readonly LegalKind[]): Promise<ConsentState[]> {
  for (let attempt = 0; ; attempt++) {
    const sentAt = Date.now()
    const answer = await api.POST('/api/queries/{queryId}', {
      params: { path: { queryId: LEGAL.myConsents } },
      body: { params: { kinds: [...kinds], at: new Date(serverNow(sentAt)).toISOString() } },
    })
    const corrected = learnServerClock(answer.response.headers.get('Date'), sentAt, Date.now())
    const page = await unwrap(Promise.resolve(answer))
    if (!corrected || attempt > 0) return (page.items ?? []) as unknown as ConsentState[]
  }
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
