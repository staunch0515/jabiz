import { CONTENT_LANGUAGES, type ContentLanguage } from '../qb/api'
import type { Condition } from '../qb/audience'
import {
  AUDIENCES,
  DIGITAL_DELIVERIES,
  formOf,
  PUBLICATION_TEXT_FIELDS,
  REFEREE_RULES,
  REFERRER_REQUIREMENTS,
  REGIONS,
  REWARD_METHODS,
  tierKey,
  type Audience,
  type DigitalDelivery,
  type PublicationDetail,
  type PublicationForm,
  type PublicationTextField,
  type ReferralInput,
  type RewardMethod,
} from './api'

/**
 * A publication's settings as a file (Q102, docs/quizbuks/plans/Q102-publication-copy-transfer.md): what makes it
 * worth reusing, without what belongs to one run or one account. Left out: the quiz version and the times (chosen on
 * import), the covers and the purchase (the account's own files and records), the invitation list, the digital
 * codes and shared content (secrets the server never reads back). Scores travel with the full score they were set
 * for, so an import onto another full score rescales them.
 */
export const SETTINGS_FORMAT = 'quizbuks.publication-settings'
export const SETTINGS_VERSION = 1

export interface SettingsTier {
  fromScore: number
  toScore: number
  reward: number
}

export interface PublicationSettings {
  format: typeof SETTINGS_FORMAT
  version: number
  name: string
  /** The full score the pass mark and the tiers were set for; null when the publication had no version. */
  fullScore: number | null
  /** How long it ran, in days (rounded up); null when it had no times. */
  durationDays: number | null
  timeLimitSec: number | null
  passScore: number | null
  pickCount: number | null
  entryFee: number
  budgetCap: number | null
  maxWinners: number | null
  tiers: SettingsTier[]
  rewardMethod: RewardMethod
  redeemDescription: string | null
  digitalDelivery: DigitalDelivery | null
  digitalDescription: string | null
  digitalSendDays: number | null
  audience: Audience
  regions: string[]
  referral: ReferralInput | null
  languages: ContentLanguage[]
  translations: Partial<Record<ContentLanguage, Partial<Record<PublicationTextField, string>>>>
  conditions: Condition[]
}

const DAY_MS = 24 * 60 * 60 * 1000

/** Whole days from one time to another, rounded up; null without both. */
export function durationDays(startAt: string | null, endAt: string | null): number | null {
  if (!startAt || !endAt) return null
  const ms = Date.parse(endAt) - Date.parse(startAt)
  return Number.isFinite(ms) && ms > 0 ? Math.ceil(ms / DAY_MS) : null
}

/** The next whole hour after `now`, the start a copy or an import suggests. */
export function nextHour(now: Date): string {
  const start = new Date(now.getTime())
  start.setMinutes(0, 0, 0)
  start.setTime(start.getTime() + 60 * 60 * 1000)
  return start.toISOString()
}

/** The end `days` days after `startAt`. */
export function endAfter(startAt: string, days: number): string {
  return new Date(Date.parse(startAt) + days * DAY_MS).toISOString()
}

const numOrNull = (value: number | string | null | undefined): number | null => {
  if (value == null || value === '') return null
  const n = Number(value)
  return Number.isFinite(n) ? n : null
}

/** The settings of a publication as read, with its matching conditions. */
export function settingsOf(detail: PublicationDetail, conditions: readonly Condition[]): PublicationSettings {
  const p = detail.publication
  return {
    format: SETTINGS_FORMAT,
    version: SETTINGS_VERSION,
    name: p.name,
    fullScore: p.fullScore,
    durationDays: durationDays(p.startAt, p.endAt),
    timeLimitSec: p.timeLimitSec,
    passScore: p.passScore,
    pickCount: p.pickCount,
    entryFee: p.entryFee,
    budgetCap: numOrNull(p.budgetCap),
    maxWinners: p.maxWinners,
    tiers: detail.tiers.map((tier) => ({ fromScore: tier.fromScore, toScore: tier.toScore,
      reward: Number(tier.reward) })),
    rewardMethod: p.rewardMethod,
    redeemDescription: p.redeemDescription,
    digitalDelivery: p.rewardMethod === 'DIGITAL_PRIZE' ? p.digitalDelivery : null,
    digitalDescription: p.rewardMethod === 'DIGITAL_PRIZE' ? p.digitalDescription : null,
    digitalSendDays: p.rewardMethod === 'DIGITAL_PRIZE' ? p.digitalSendDays : null,
    audience: p.audience,
    regions: p.audience === 'INVITED' ? [] : [...p.regions],
    referral: p.referral ? { ...p.referral, refereeRules: [...p.referral.refereeRules] } : null,
    languages: [...p.languages],
    translations: p.translations,
    // An invited publication has no conditions (its list stands in for them).
    conditions: p.audience === 'INVITED' ? [] : conditions.map((c) => ({ ...c })),
  }
}

/** The file of the settings, named after the publication. */
export function settingsFile(settings: PublicationSettings): { blob: Blob; name: string; size: number } {
  const blob = new Blob([JSON.stringify(settings, null, 2)], { type: 'application/json' })
  const base = settings.name.normalize('NFKC').replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return { blob, name: `${base || 'publication'}.publication.json`, size: blob.size }
}

/** Why a file is not settings this page can read. */
export type SettingsProblem = 'notSettings' | 'newerVersion'

const isObject = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === 'object' && !Array.isArray(value)
const intOrNull = (value: unknown): number | null =>
  typeof value === 'number' && Number.isInteger(value) && value >= 0 ? value : null
const textOrNull = (value: unknown): string | null => (typeof value === 'string' && value.trim() ? value : null)
const oneOf = <T extends string>(values: readonly T[], value: unknown): T | null =>
  (values as readonly unknown[]).includes(value) ? (value as T) : null

/**
 * Reads a settings file. Only the shapes are checked, and values of the wrong kind are left out rather than guessed:
 * the draft is saved through the same process as one typed by hand, which reports whatever else is wrong.
 */
export function parseSettings(value: unknown): { settings: PublicationSettings } | { problem: SettingsProblem } {
  if (!isObject(value) || value.format !== SETTINGS_FORMAT || typeof value.version !== 'number') {
    return { problem: 'notSettings' }
  }
  if (value.version > SETTINGS_VERSION) return { problem: 'newerVersion' }
  const tiers = Array.isArray(value.tiers) ? value.tiers.flatMap((tier): SettingsTier[] => {
    if (!isObject(tier)) return []
    const fromScore = intOrNull(tier.fromScore)
    const toScore = intOrNull(tier.toScore)
    const reward = intOrNull(tier.reward)
    return fromScore === null || toScore === null || reward === null ? [] : [{ fromScore, toScore, reward }]
  }) : []
  const referral = isObject(value.referral) ? {
    quota: intOrNull(value.referral.quota),
    method: oneOf(REWARD_METHODS, value.referral.method) ?? 'PLATFORM',
    amount: intOrNull(value.referral.amount),
    description: textOrNull(value.referral.description),
    referrerRequires: oneOf(REFERRER_REQUIREMENTS, value.referral.referrerRequires) ?? 'NONE',
    refereeRules: Array.isArray(value.referral.refereeRules)
      ? REFEREE_RULES.filter((rule) => (value.referral as Record<string, unknown[]>).refereeRules.includes(rule))
      : [],
    days: intOrNull(value.referral.days),
  } : null
  const languages = Array.isArray(value.languages)
    ? CONTENT_LANGUAGES.filter((language) => (value.languages as unknown[]).includes(language))
      .sort((a, b) => (value.languages as unknown[]).indexOf(a) - (value.languages as unknown[]).indexOf(b))
    : []
  const translations: PublicationSettings['translations'] = {}
  if (isObject(value.translations)) {
    for (const language of CONTENT_LANGUAGES) {
      const texts = value.translations[language]
      if (!isObject(texts)) continue
      const kept = Object.fromEntries(PUBLICATION_TEXT_FIELDS.flatMap((field) =>
        typeof texts[field] === 'string' ? [[field, texts[field] as string]] : []))
      if (Object.keys(kept).length > 0) translations[language] = kept
    }
  }
  const conditions = Array.isArray(value.conditions) ? value.conditions.flatMap((c): Condition[] => {
    if (!isObject(c) || typeof c.key !== 'string') return []
    return [{
      key: c.key,
      ...(Array.isArray(c.options) ? { options: c.options.filter((o): o is string => typeof o === 'string') } : {}),
      ...(typeof c.min === 'number' ? { min: c.min } : {}),
      ...(typeof c.max === 'number' ? { max: c.max } : {}),
    }]
  }) : []
  const audience = oneOf(AUDIENCES, value.audience) ?? 'PUBLIC'
  return {
    settings: {
      format: SETTINGS_FORMAT,
      version: value.version,
      name: typeof value.name === 'string' ? value.name : '',
      fullScore: intOrNull(value.fullScore),
      durationDays: intOrNull(value.durationDays),
      timeLimitSec: intOrNull(value.timeLimitSec),
      passScore: intOrNull(value.passScore),
      pickCount: intOrNull(value.pickCount),
      entryFee: intOrNull(value.entryFee) ?? 0,
      budgetCap: intOrNull(value.budgetCap),
      maxWinners: intOrNull(value.maxWinners),
      tiers,
      rewardMethod: oneOf(REWARD_METHODS, value.rewardMethod) ?? 'PLATFORM',
      redeemDescription: textOrNull(value.redeemDescription),
      digitalDelivery: oneOf(DIGITAL_DELIVERIES, value.digitalDelivery),
      digitalDescription: textOrNull(value.digitalDescription),
      digitalSendDays: intOrNull(value.digitalSendDays),
      audience,
      regions: audience === 'INVITED' || !Array.isArray(value.regions)
        ? [] : REGIONS.filter((region) => (value.regions as unknown[]).includes(region)),
      referral,
      languages,
      translations,
      conditions: audience === 'INVITED' ? [] : conditions,
    },
  }
}

/** A score set for one full score, at the same share of another. */
export const rescale = (score: number, from: number, to: number): number => Math.round((score * to) / from)

/**
 * Tiers set for one full score, on another: each tier starts at the same share of the new full score, ends where the
 * next one starts and the last one ends at the full score, so the tiers still cover every score once. Tiers that
 * shrink to nothing are dropped. Unchanged when either full score is unknown or they are the same.
 */
export function rescaleTiers<T extends { fromScore: number; toScore: number }>(tiers: readonly T[],
  from: number | null, to: number | null): T[] {
  if (!from || !to || from === to || tiers.length === 0) return [...tiers]
  const sorted = [...tiers].sort((a, b) => a.fromScore - b.fromScore)
  const starts = sorted.map((tier) => rescale(tier.fromScore, from, to))
  const scaled: T[] = []
  sorted.forEach((tier, i) => {
    const fromScore = starts[i]
    const last = i === sorted.length - 1
    const toScore = last ? (tier.toScore === from ? to : rescale(tier.toScore, from, to)) : starts[i + 1] - 1
    if (toScore >= fromScore) scaled.push({ ...tier, fromScore, toScore })
  })
  return scaled
}

/** Where an imported draft or a copy goes: the version, its quiz and full score, the times and the name. */
export interface Placement {
  quizId: string
  versionId: string
  fullScore: number | null
  startAt: string | null
  endAt: string | null
  name: string
}

/**
 * A form's pass mark and tiers set for one full score, on another (a copy or an import onto a version with another
 * full score). Unchanged when either is unknown or they are the same.
 */
export function rescaleForm(form: PublicationForm, from: number | null, to: number | null): PublicationForm {
  if (!from || !to || from === to) return form
  const pass = form.passScore.trim() === '' ? null : Number(form.passScore)
  const tiers = rescaleTiers(form.tiers.map((tier) => ({ ...tier, fromScore: Number(tier.fromScore),
    toScore: Number(tier.toScore) })), from, to)
  return {
    ...form,
    passScore: pass === null || !Number.isFinite(pass) ? form.passScore : String(rescale(pass, from, to)),
    tiers: tiers.map((tier) => ({ ...tier, fromScore: String(tier.fromScore), toScore: String(tier.toScore) })),
  }
}

/** The form of a new draft from settings, on the version, times and name chosen. */
export function formOfSettings(settings: PublicationSettings, placement: Placement): PublicationForm {
  const text = (value: number | null) => (value == null ? '' : String(value))
  // Translated names were of the file's name: a draft named otherwise keeps none of them.
  const renamed = placement.name.trim() !== settings.name.trim()
  const translations = Object.fromEntries(Object.entries(settings.translations).map(([language, texts]) => {
    const kept = { ...texts }
    if (renamed) delete kept.name
    return [language, kept]
  })) as PublicationForm['translations']
  const form: PublicationForm = {
    ...formOf(null),
    name: placement.name.trim(),
    quizId: placement.quizId,
    versionId: placement.versionId,
    startAt: placement.startAt,
    endAt: placement.endAt,
    regions: [...settings.regions],
    timeLimitSec: text(settings.timeLimitSec),
    passScore: text(settings.passScore),
    pickCount: text(settings.pickCount),
    entryFee: settings.entryFee ? String(settings.entryFee) : '',
    budgetCap: text(settings.budgetCap),
    maxWinners: text(settings.maxWinners),
    tiers: settings.tiers.map((tier) => ({ key: tierKey(), tierId: null, fromScore: String(tier.fromScore),
      toScore: String(tier.toScore), reward: String(tier.reward) })),
    rewardMethod: settings.rewardMethod,
    redeemDescription: settings.redeemDescription ?? '',
    digitalDelivery: settings.digitalDelivery,
    digitalDescription: settings.digitalDescription ?? '',
    digitalSendDays: text(settings.digitalSendDays),
    audience: settings.audience,
    referralEnabled: settings.referral !== null,
    ...(settings.referral ? {
      referralQuota: text(settings.referral.quota),
      referralMethod: settings.referral.method,
      referralAmount: text(settings.referral.amount),
      referralDescription: settings.referral.description ?? '',
      referrerRequires: settings.referral.referrerRequires,
      refereeRules: [...settings.referral.refereeRules],
      referralDays: text(settings.referral.days) || '7',
    } : {}),
    languages: [...settings.languages],
    translations,
    storedTranslations: {},
    translationHashes: {},
  }
  return rescaleForm(form, settings.fullScore, placement.fullScore)
}
