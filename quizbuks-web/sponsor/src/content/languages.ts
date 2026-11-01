import type { ContentLanguage } from '../qb/api'
import { contentFieldOf, type Draft, type LanguageStatus, type Material, type Translation } from './api'

/*
 * A quiz's other languages as the editor shows them (Q73a, docs/quizbuks/plans/Q73-content-languages.md §2): the
 * keys of the elements, the hash of a base text a translation was made from, and what each language still lacks. A
 * copy of `content.ContentLanguages` / `QuizLanguages`: the server decides when a version is made; the page only
 * marks what to do. The hash's vectors are shared with `ContentLanguagesTest`.
 */

export const TITLE_KEY = 'quiz.title'
export const INTRO_KEY = 'quiz.intro'
export const stemKey = (questionId: string) => `question:${questionId}.stem`
export const questionImageKey = (questionId: string) => `question:${questionId}.image`
export const optionTextKey = (optionId: string) => `option:${optionId}.text`
export const optionImageKey = (optionId: string) => `option:${optionId}.image`

const blank = (text: string | null | undefined) => text == null || text.trim() === ''

/**
 * The hash a translation records of its base text: the first 16 hexadecimal characters of the SHA-256 of its NFC form
 * in UTF-8; null for no text.
 */
export async function sourceHash(text: string | null | undefined): Promise<string | null> {
  if (blank(text)) return null
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text!.normalize('NFC')))
  return Array.from(new Uint8Array(digest).slice(0, 8), (b) => b.toString(16).padStart(2, '0')).join('')
}

/** One element of the base's text, as the translation page lists it. */
export interface SourceText {
  key: string
  text: string | null
  /** Where it is: the quiz's heading, or the question (from 1) and the option (from 1) it belongs to. */
  question: number | null
  option: number | null
  /** Markdown (the introduction and a question's text) or plain text. */
  markdown: boolean
  /** The most characters of its translation: the base field's. */
  maxLength: number
}

/** Every text of the draft that can be translated, in the base's order. */
export function sourceTexts(draft: Draft, limits: { title: number; intro: number; stem: number; option: number })
  : SourceText[] {
  const texts: SourceText[] = [
    { key: TITLE_KEY, text: draft.quiz.title, question: null, option: null, markdown: false, maxLength: limits.title },
    { key: INTRO_KEY, text: draft.quiz.intro, question: null, option: null, markdown: true, maxLength: limits.intro },
  ]
  draft.questions.forEach((q, i) => {
    texts.push({ key: stemKey(q.questionId), text: q.stem, question: i + 1, option: null, markdown: true,
      maxLength: limits.stem })
    q.options.forEach((o, j) => texts.push({ key: optionTextKey(o.optionId), text: o.text, question: i + 1,
      option: j + 1, markdown: false, maxLength: limits.option }))
  })
  return texts
}

/** The state of one text in a language: nothing to translate, missing, out of date, or done. */
export type TextState = 'none' | 'missing' | 'stale' | 'done'

/** The hashes of the base texts, by text (the page computes them once per text). */
export type Hashes = ReadonlyMap<string, string | null>

/** Computes the hashes of the given texts. */
export async function hashesOf(texts: (string | null)[]): Promise<Map<string, string | null>> {
  const unique = [...new Set(texts.filter((t): t is string => !blank(t)))]
  const hashes = await Promise.all(unique.map(sourceHash))
  return new Map(unique.map((text, i) => [text, hashes[i]]))
}

export function textState(source: SourceText, translation: Translation | undefined, hashes: Hashes): TextState {
  if (blank(source.text)) return 'none'
  if (!translation || blank(translation.text)) return 'missing'
  const hash = hashes.get(source.text!)
  return hash !== undefined && hash !== translation.sourceHash ? 'stale' : 'done'
}

/** The language's translations, by key. */
export function translationsIn(draft: Draft, language: ContentLanguage): Map<string, Translation> {
  return new Map(draft.translations.filter((t) => t.language === language).map((t) => [t.key, t]))
}

/** The material of a language standing for a base material, if it has one. */
export function variantOf(draft: Draft, base: Material, language: ContentLanguage): Material | undefined {
  return draft.variants.find((v) => v.variantOf === base.materialId && v.language === language)
}

/** Whether a material has what a version requires: a title and the content of its kind. */
export function materialComplete(m: Material): boolean {
  if (blank(m.title)) return false
  switch (contentFieldOf(m.kind)) {
    case 'body':
      return !blank(m.body)
    case 'url':
      return !blank(m.url)
    case 'pdf':
      return m.pdf != null
    case 'audio':
      return m.audio != null
    case 'images':
      return m.images.length > 0
  }
}

/** What a language lacks, as `QuizLanguages.Status` says it (the page's reading; the server's decides). */
export function statusOf(draft: Draft, language: ContentLanguage, texts: SourceText[], hashes: Hashes)
  : LanguageStatus {
  const translations = translationsIn(draft, language)
  const missing: string[] = []
  const stale: string[] = []
  for (const source of texts) {
    const state = textState(source, translations.get(source.key), hashes)
    if (state === 'missing') missing.push(source.key)
    if (state === 'stale') stale.push(source.key)
  }
  const materials = draft.materials.flatMap((m, i) => {
    const own = variantOf(draft, m, language)
    return own && !materialComplete(own) ? [i + 1] : []
  })
  return { language, missing, stale, materials }
}

/** How many things a language still needs. */
export const pending = (status: LanguageStatus) =>
  status.missing.length + status.stale.length + status.materials.length
