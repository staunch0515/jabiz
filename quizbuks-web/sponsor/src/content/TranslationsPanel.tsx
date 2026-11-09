import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  Input,
  Label,
  notify,
  SegmentedControl,
  Textarea,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Pencil, Plus, Save, Search, Trash2 } from 'lucide-react'
import { useEffect, useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AI_QUERY } from '../ai/AiQuestionsPanel'
import { acceptAiTranslations, type AiJob } from '../ai/api'
import { AiTranslatePanel, useAiTranslationJob } from '../ai/AiTranslatePanel'
import { SPONSOR } from '../messages'
import { CONTENT_LANGUAGES, type ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { fieldProblem } from '../qb/problems'
import {
  deleteMaterial,
  saveTranslations,
  searchTranslations,
  setLanguages,
  type Draft,
  type EditOutput,
  type Material,
  type TranslationEntry,
  type TranslationRow,
} from './api'
import { ContentImage } from './ContentImage'
import { FileField, formProblem, ProblemList, useEditing } from './editing'
import {
  hashesOf,
  materialComplete,
  COVER_KEY,
  optionImageKey,
  optionTextKey,
  pending,
  questionImageKey,
  sourceTexts,
  stemKey,
  statusOf,
  textState,
  translationsIn,
  variantOf,
  type Hashes,
  type SourceText,
  type TextState,
} from './languages'
import { LIMITS, TRANSLATION_PAGE } from './limits'
import { MaterialDialog, useKindLabel } from './MaterialsPanel'
import { QUIZZES_QUERY } from './paths'

const TEXT_LIMITS = { title: LIMITS.title, intro: LIMITS.intro, stem: LIMITS.stem, option: LIMITS.option }

const STATE_VARIANT: Record<Exclude<TextState, 'none'>, 'default' | 'secondary' | 'outline'> = {
  done: 'secondary', missing: 'outline', stale: 'default',
}

/** The base texts' hashes, computed once the texts are known (Web Crypto is asynchronous). */
function useHashes(texts: SourceText[]): Hashes | null {
  const [hashes, setHashes] = useState<Hashes | null>(null)
  const joined = texts.map((s) => s.text ?? '').join('\u0000')
  useEffect(() => {
    let live = true
    void hashesOf(texts.map((s) => s.text)).then((h) => live && setHashes(h))
    return () => {
      live = false
    }
    // The texts by their content: a new array of the same texts needs no new hashes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [joined])
  return hashes
}

/** Which of the platform's languages the quiz has besides its base; a language taken away loses its translations. */
function LanguagesForm({ draft }: { draft: Draft }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const editing = useEditing()
  const base = draft.quiz.language
  const present = draft.quiz.translationLanguages
  const change = useMutation({
    mutationFn: (languages: ContentLanguage[]) => setLanguages(editing.quizId, languages, editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('translations.languagesSaved'))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const others = CONTENT_LANGUAGES.filter((l) => l !== base)
  return (
    <fieldset className="flex flex-col gap-3 rounded-lg border p-4" data-testid="translation-languages">
      <legend className="px-1 text-sm font-medium">{t('translations.languages')}</legend>
      <p className="text-muted-foreground text-sm">
        {t('translations.languagesHint', { base: tQb(`languages.${base}`) })}
      </p>
      <div className="flex flex-wrap gap-4">
        {others.map((language) => {
          const on = present.includes(language)
          const checkbox = (
            <div className="flex items-center gap-2">
              <Checkbox id={`translation-language-${language}`} checked={on}
                disabled={change.isPending || editing.stale} data-testid={`translation-language-${language}`}
                onCheckedChange={(checked) => {
                  if (checked === true && !on) change.mutate([...present, language])
                }} />
              <Label htmlFor={`translation-language-${language}`} className="font-normal">
                {tQb(`languages.${language}`)}
              </Label>
            </div>
          )
          if (!on) return <div key={language}>{checkbox}</div>
          return (
            <div key={language} className="flex items-center gap-2">
              {checkbox}
              <ConfirmDialog destructive title={t('translations.removeTitle', { language: tQb(`languages.${language}`) })}
                description={t('translations.removeText')} confirmLabel={t('translations.remove')}
                cancelLabel={t('common.cancel')}
                onConfirm={() => change.mutateAsync(present.filter((l) => l !== language)).then(() => undefined)}
                trigger={
                  <Button variant="ghost" size="sm" disabled={change.isPending || editing.stale}
                    aria-label={t('translations.removeTitle', { language: tQb(`languages.${language}`) })}
                    data-testid={`translation-language-remove-${language}`}>
                    <Trash2 aria-hidden />
                  </Button>
                } />
            </div>
          )
        })}
      </div>
      <ProblemList problems={formProblem(change.error, [])} />
    </fieldset>
  )
}

/** Where a text belongs, as the sponsor knows it: the title, the introduction, question 3, option 2 of question 3. */
function useSourceLabel() {
  const { t } = useTranslation(SPONSOR)
  return (source: SourceText) => source.question === null
    ? t(source.markdown ? 'translations.intro' : 'translations.title')
    : source.option === null ? t('translations.question', { n: source.question })
      : t('translations.option', { n: source.question, m: source.option })
}

/** One text of a form: the base's, its saved translation in the language, and its state (null while unknown). */
interface FormText {
  source: SourceText
  saved: { text: string | null; translatedBy?: string | null } | undefined
  state: TextState | null
}

/**
 * Texts of the quiz side by side with their translations in one language, saved together. Q73d-1: with a ready AI
 * job of the language, each text shows the AI's suggestion under it; one used ("Use", or "Use all") fills the box and
 * must be ticked as checked (or changed) before the save, which writes the checked ones as the AI's
 * ({@code QB_AI_TRANSLATION_ACCEPT}) and the others as the sponsor's ({@code QB_TRANSLATION_SAVE}). The quiz's own
 * texts are one such form, and each page of the questions another (F-S6c).
 */
function TextsForm({ language, items, visible, ai, useAllLabel, saveTestId = 'translation-save', onChanged }: {
  language: ContentLanguage
  items: FormText[]
  /** Which texts are shown (the others keep their values). */
  visible?: (item: FormText) => boolean
  ai: AiJob | null
  /** "Use all", or "Use all on this page". */
  useAllLabel: string
  saveTestId?: string
  /** How many texts are changed and not saved yet. */
  onChanged?: (count: number) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const editing = useEditing()
  const id = useId()
  const label = useSourceLabel()
  const texts = items.map((item) => item.source)
  const savedText = (key: string) => items.find((item) => item.source.key === key)?.saved?.text ?? ''
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(items.map((item) => [item.source.key, item.saved?.text ?? ''])))
  // The AI's suggestions of this language (Q73d-1), the ones used and whether each was checked.
  const suggestions = useMemo(() => new Map((ai?.translations ?? []).map((c) => [c.key, c])), [ai])
  const [used, setUsed] = useState<Record<string, boolean>>({})
  const [local, setLocal] = useState<string | null>(null)
  const takeSuggestion = (key: string) => {
    const suggestion = suggestions.get(key)
    if (!suggestion) return
    setValues((v) => ({ ...v, [key]: suggestion.text }))
    setUsed((u) => ({ ...u, [key]: false }))
  }
  const changed = texts.filter((s) => (values[s.key] ?? '') !== savedText(s.key))
  useEffect(() => onChanged?.(changed.length), [onChanged, changed.length])
  useEffect(() => () => onChanged?.(0), [onChanged])
  // A used suggestion goes as the AI's once checked; changed or cleared afterwards, it is the sponsor's own.
  const fromAi = changed.filter((s) => s.key in used && (values[s.key] ?? '').trim() !== '')
  const own = changed.filter((s) => !fromAi.includes(s))
  const save = useMutation({
    mutationFn: async () => {
      let revision = editing.revision()
      let output: EditOutput | null = null
      if (fromAi.length > 0 && ai) {
        output = await acceptAiTranslations(ai.aiJobId, editing.quizId, fromAi.map((s) => ({
          candidateNo: suggestions.get(s.key)!.no, text: values[s.key]!, reviewed: true as const })), revision)
        revision = output.revision
      }
      if (own.length > 0) {
        output = await saveTranslations(editing.quizId, language,
          own.map((s): TranslationEntry => ({ key: s.key, text: (values[s.key] ?? '').trim() === '' ? null
            : values[s.key] })), revision)
      }
      return output!
    },
    onSuccess: async (output) => {
      notify.success(t('translations.saved'))
      await queryClient.invalidateQueries({ queryKey: AI_QUERY })
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const submit = () => {
    if (fromAi.some((s) => !used[s.key])) {
      setLocal(t('ai.translate.reviewFirst'))
      return
    }
    setLocal(null)
    save.mutate()
  }
  const usable = items.filter((item) => suggestions.has(item.source.key))
  return (
    <div className="flex flex-col gap-4">
      {usable.length > 0 && (
        <Button variant="outline" size="sm" className="w-fit" data-testid="ai-translate-use-all"
          onClick={() => texts.forEach((s) => !(s.key in used) && takeSuggestion(s.key))}>
          {useAllLabel}
        </Button>
      )}
      <ol className="flex flex-col gap-3">
        {items.filter((item) => !visible || visible(item)).map(({ source, saved, state }) => {
          const fieldId = `${id}-${source.key}`
          const problem = fieldProblem(save.error, `entries[${own.indexOf(source)}].text`)
          return (
            <li key={source.key} className="grid gap-2 rounded-md border p-3 md:grid-cols-2"
              data-testid="translation-text" data-key={source.key} data-state={state ?? 'unknown'}>
              <div className="flex flex-col gap-1">
                <span className="flex items-center gap-2 text-sm font-medium">
                  {label(source)}
                  {state && state !== 'none' && (
                    <Badge variant={STATE_VARIANT[state]} data-testid="translation-state">
                      {t(`translations.states.${state}`)}
                    </Badge>
                  )}
                  {/* Q73d-1: the saved translation came from the AI, checked by the sponsor. */}
                  {saved?.translatedBy === 'AI' && (
                    <Badge variant="outline" data-testid="translation-by-ai">{t('ai.translate.byAi')}</Badge>
                  )}
                </span>
                <p className="text-muted-foreground text-sm whitespace-pre-line" data-testid="translation-source">
                  {source.text}
                </p>
              </div>
              <div className="flex flex-col gap-1">
                <Label htmlFor={fieldId} className="sr-only">
                  {t('translations.translationOf', { what: label(source) })}
                </Label>
                {source.markdown ? (
                  <Textarea id={fieldId} rows={3} value={values[source.key] ?? ''} maxLength={source.maxLength}
                    aria-invalid={problem ? true : undefined} data-testid="translation-input"
                    onChange={(event) => setValues((v) => ({ ...v, [source.key]: event.target.value }))} />
                ) : (
                  <Input id={fieldId} value={values[source.key] ?? ''} maxLength={source.maxLength}
                    autoComplete="off" aria-invalid={problem ? true : undefined} data-testid="translation-input"
                    onChange={(event) => setValues((v) => ({ ...v, [source.key]: event.target.value }))} />
                )}
                {problem && <p className="text-destructive text-sm">{problem}</p>}
                {suggestions.has(source.key) && !(source.key in used) && (
                  <div className="bg-muted/50 flex flex-col gap-1 rounded-md p-2" data-testid="ai-suggestion">
                    <span className="text-muted-foreground text-xs">{t('ai.translate.candidate')}</span>
                    <p className="text-sm whitespace-pre-line" lang={language}>
                      {suggestions.get(source.key)!.text}
                    </p>
                    <Button size="sm" variant="outline" className="w-fit" data-testid="ai-suggestion-use"
                      onClick={() => takeSuggestion(source.key)}>{t('ai.translate.use')}</Button>
                  </div>
                )}
                {source.key in used && (
                  <div className="flex items-center gap-2">
                    <Checkbox id={`${fieldId}-reviewed`} checked={used[source.key]}
                      data-testid="ai-suggestion-reviewed"
                      onCheckedChange={(checked) => setUsed((u) => ({ ...u, [source.key]: checked === true }))} />
                    <Label htmlFor={`${fieldId}-reviewed`} className="font-normal">{t('ai.translate.reviewed')}</Label>
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ol>
      <ProblemList problems={formProblem(save.error, own.map((_, i) => `entries[${i}].text`))}
        testId="translation-error" />
      {local && <p role="alert" className="text-destructive text-sm" data-testid="ai-translate-review-first">{local}</p>}
      <Button className="w-fit" disabled={changed.length === 0 || save.isPending || editing.stale}
        aria-busy={save.isPending || undefined} data-testid={saveTestId} onClick={submit}>
        <Save aria-hidden />
        {t('translations.save', { count: changed.length })}
      </Button>
    </div>
  )
}

/** The quiz's own texts (its title and introduction): few, so all of them at once, from the draft. */
function QuizTextsSection({ draft, language, texts, hashes, ai, onlyPending }: {
  draft: Draft
  language: ContentLanguage
  texts: SourceText[]
  hashes: Hashes | null
  ai: AiJob | null
  onlyPending: boolean
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const saved = useMemo(() => translationsIn(draft, language), [draft, language])
  const items = texts.filter((s) => s.question === null && s.text && s.text.trim() !== '').map((source): FormText => ({
    source, saved: saved.get(source.key),
    state: hashes ? textState(source, saved.get(source.key), hashes) : null,
  }))
  // The filter hides what is done, once the states are known; the form keeps every text's value.
  const visible = (item: FormText) => !onlyPending || item.state !== 'done'
  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="translation-texts">
      <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.texts')}</h3>
      {!items.some(visible) && <p className="text-muted-foreground text-sm">{t('translations.nothingPending')}</p>}
      <TextsForm language={language} items={items} visible={visible} ai={ai} useAllLabel={t('ai.translate.useAll')} />
    </section>
  )
}

/** One image of the quiz in a language: the base's, and the language's own in its place if it has one. */
interface ImageRow {
  key: string
  base: string | null
  own: string | null
  what: string
}

/** Images in one language, each the base's unless the language has its own (Q98b: the quiz's cover too). */
function ImagesList({ language, images }: { language: ContentLanguage; images: ImageRow[] }) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const save = useMutation({
    mutationFn: (entry: TranslationEntry) => saveTranslations(editing.quizId, language, [entry],
      editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('translations.imageSaved'))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  return (
    <>
      <ol className="flex flex-col gap-3">
        {images.map((image) => (
          <li key={image.key} className="flex flex-wrap items-start gap-4 rounded-md border p-3"
            data-testid="translation-image" data-key={image.key}>
            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium">{image.what}</span>
              {image.base ? <ContentImage fileId={image.base} width={120} />
                : <span className="text-muted-foreground text-xs">{t('translations.noBaseCover')}</span>}
            </div>
            <FileField kind="image" label={t('translations.ownImage')}
              uploadLabel={image.own ? t('translations.replaceImage') : t('translations.uploadImage')}
              value={image.own} disabled={save.isPending || editing.stale}
              testId="translation-image-file" onChange={(fileId) => save.mutate({ key: image.key, image: fileId })} />
          </li>
        ))}
      </ol>
      <ProblemList problems={formProblem(save.error, [])} />
    </>
  )
}

/** The quiz's cover in one language (Q98b): the base's unless the language has its own. */
function CoverSection({ draft, language }: { draft: Draft; language: ContentLanguage }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const own = translationsIn(draft, language).get(COVER_KEY)?.image ?? null
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`${id}-title`} data-testid="translation-images">
      <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.images')}</h3>
      <p className="text-muted-foreground text-sm">{t('translations.imagesHint')}</p>
      <ImagesList language={language}
        images={[{ key: COVER_KEY, base: draft.quiz.cover, own, what: t('translations.cover') }]} />
    </section>
  )
}

/** A short key of what a form's texts hold: its texts, their saved translations and states. */
function contentKey(items: FormText[]): string {
  const text = items.map((item) => [item.source.key, item.source.text, item.saved?.text, item.saved?.translatedBy,
    item.state].join('\u0001')).join('\u0002')
  let hash = 5381
  for (let i = 0; i < text.length; i++) hash = (Math.imul(hash, 33) ^ text.charCodeAt(i)) >>> 0
  return `${items.length}-${hash.toString(36)}`
}

/** The texts of a page of questions as the form takes them. */
function pageTexts(rows: TranslationRow[]): FormText[] {
  return rows.flatMap((row) => [
    ...(row.stem && row.stem.trim() !== '' ? [{
      source: { key: stemKey(row.questionId), text: row.stem, question: row.no, option: null, markdown: true,
        maxLength: LIMITS.stem },
      saved: row.stemText === null ? undefined : { text: row.stemText, translatedBy: row.stemBy },
      state: row.stemState }] : []),
    ...row.options.filter((o) => o.text && o.text.trim() !== '').map((o): FormText => ({
      source: { key: optionTextKey(o.optionId), text: o.text, question: row.no, option: o.no, markdown: false,
        maxLength: LIMITS.option },
      saved: o.translation === null ? undefined : { text: o.translation, translatedBy: o.translatedBy },
      state: o.state })),
  ])
}

/**
 * The quiz's questions in one language, a page at a time (F-S6c: `qb.sponsor.quiz-translations`, 50 a page): their
 * texts and their options' side by side with the translations, saved a page at a time, and their own images.
 * Searched by the text of a question or an option, or only those with a text missing or out of date. A page with
 * changes not saved yet keeps the sponsor on it.
 */
function QuestionsSection({ language, ai, onlyPending, onHeld }: {
  language: ContentLanguage
  ai: AiJob | null
  onlyPending: boolean
  /** Whether the page has changes not saved yet (the filter then waits too). */
  onHeld: (held: boolean) => void
}) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const id = useId()
  const [typed, setTyped] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [unsaved, setUnsaved] = useState(0)
  // A new filter or language starts at the first page (adjusted while rendering, as React advises).
  const [shownFor, setShownFor] = useState({ language, onlyPending })
  if (shownFor.language !== language || shownFor.onlyPending !== onlyPending) {
    setShownFor({ language, onlyPending })
    setPage(0)
  }
  const list = useQuery({
    queryKey: [...QUIZZES_QUERY, 'translations', editing.quizId, language, search, onlyPending, page],
    queryFn: () => searchTranslations({ quizId: editing.quizId, language, search, untranslated: onlyPending,
      offset: page * TRANSLATION_PAGE, limit: TRANSLATION_PAGE }),
    placeholderData: keepPreviousData,
  })
  const rows = useMemo(() => list.data?.items ?? [], [list.data])
  const total = list.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / TRANSLATION_PAGE))
  // A page emptied by translating its last texts (with the filter) shows the last one there is.
  if (list.data && !list.isPlaceholderData && rows.length === 0 && page > 0 && page > pages - 1) setPage(pages - 1)
  const items = useMemo(() => pageTexts(rows), [rows])
  const images: ImageRow[] = rows.flatMap((row) => [
    ...(row.image ? [{ key: questionImageKey(row.questionId), base: row.image, own: row.ownImage,
      what: t('translations.questionImage', { n: row.no }) }] : []),
    ...row.options.flatMap((o) => o.image ? [{ key: optionImageKey(o.optionId), base: o.image, own: o.ownImage,
      what: t('translations.optionImage', { n: row.no, m: o.no }) }] : []),
  ])
  const held = unsaved > 0
  useEffect(() => onHeld(held), [onHeld, held])
  useEffect(() => () => onHeld(false), [onHeld])
  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="translation-questions">
      <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.questions')}</h3>
      <form role="search" className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
        event.preventDefault()
        if (held) return
        setSearch(typed.trim())
        setPage(0)
      }}>
        <div className="flex flex-col gap-1">
          <Label htmlFor={`${id}-q`}>{t('translations.search')}</Label>
          <Input id={`${id}-q`} type="search" className="w-64" value={typed} maxLength={100}
            placeholder={t('translations.searchHint')} aria-describedby={`${id}-hint`}
            data-testid="translations-search" onChange={(event) => setTyped(event.target.value)} />
          <span id={`${id}-hint`} className="sr-only">{t('translations.searchHint')}</span>
        </div>
        <Button type="submit" variant="outline" disabled={held} data-testid="translations-search-submit">
          <Search aria-hidden />
          {t('bank.searchButton')}
        </Button>
        {search && (
          <Button type="button" variant="ghost" disabled={held} data-testid="translations-search-clear" onClick={() => {
            setTyped('')
            setSearch('')
            setPage(0)
          }}>
            {t('bank.clearSearch')}
          </Button>
        )}
        {(search || onlyPending) && list.data && (
          <span role="status" className="text-muted-foreground text-sm" data-testid="translations-found">
            {t('bank.found', { count: total })}
          </span>
        )}
      </form>
      {list.data && rows.length === 0 ? (
        <p className="text-muted-foreground text-sm" data-testid="translations-no-questions">
          {search ? t('bank.noMatch') : onlyPending ? t('translations.nothingPending') : t('questions.none')}
        </p>
      ) : (
        <>
          {/* Another page, or a save or a reload that changed what the server has, starts the texts again from it
              (the same answer read again keeps what is typed). */}
          <TextsForm key={`${contentKey(items)}-${ai?.aiJobId ?? ''}`} language={language}
            items={items} ai={ai} useAllLabel={t('translations.useAllPage')} saveTestId="translation-page-save"
            onChanged={setUnsaved} />
          {images.length > 0 && (
            <div className="flex flex-col gap-3" data-testid="translation-page-images">
              <h4 className="text-sm font-semibold">{t('translations.pageImages')}</h4>
              <ImagesList language={language} images={images} />
            </div>
          )}
          {pages > 1 && (
            <nav aria-label={t('bank.pages')} className="flex flex-col items-center gap-2"
              data-testid="translations-pages">
              <div className="flex flex-wrap items-center justify-center gap-2">
                <Button variant="outline" size="sm" disabled={page === 0 || held} data-testid="translations-previous"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}>
                  <ChevronLeft aria-hidden />
                  {t('bank.previous')}
                </Button>
                <span className="text-sm" aria-live="polite" data-testid="translations-page">
                  {t('bank.pageOf', { page: page + 1, pages })}
                </span>
                <Button variant="outline" size="sm" disabled={page + 1 >= pages || held}
                  data-testid="translations-next" onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}>
                  {t('bank.next')}
                  <ChevronRight aria-hidden />
                </Button>
              </div>
              {held && (
                <p className="text-muted-foreground text-sm" data-testid="translations-save-first">
                  {t('translations.saveFirst')}
                </p>
              )}
            </nav>
          )}
        </>
      )}
    </section>
  )
}

/** The base materials and, for each, the language's own material or the base's shown in its place. */
function MaterialsSection({ draft, language }: { draft: Draft; language: ContentLanguage }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const editing = useEditing()
  const kindLabel = useKindLabel()
  const id = useId()
  const [open, setOpen] = useState<{ base: Material; own: Material | null; number: number } | null>(null)
  const remove = (own: Material) => deleteMaterial(editing.quizId, own.materialId, editing.revision()).then(
    async (output) => {
      notify.success(t('translations.materialRemoved'))
      await editing.edited(output)
    },
    (error: unknown) => {
      editing.failed(error)
      throw error
    },
  )
  if (draft.materials.length === 0) return null
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`${id}-title`} data-testid="translation-materials">
      <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.materials')}</h3>
      <p className="text-muted-foreground text-sm">
        {t('translations.materialsHint', { base: tQb(`languages.${draft.quiz.language}`) })}
      </p>
      <ol className="flex flex-col gap-2">
        {draft.materials.map((base, index) => {
          const own = variantOf(draft, base, language)
          return (
            <li key={base.materialId} className="flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
              data-testid="translation-material" data-own={own ? 'yes' : 'no'}>
              <span className="flex flex-col gap-1">
                <span className="text-sm font-medium">
                  {t('translations.materialRow', { number: index + 1, kind: kindLabel(base.kind) })}
                  {' · '}{own?.title ?? base.title}
                </span>
                <span className="text-muted-foreground flex items-center gap-2 text-xs">
                  {own
                    ? t('translations.ownMaterial')
                    : t('translations.baseMaterial', { base: tQb(`languages.${draft.quiz.language}`) })}
                  {own && !materialComplete(own) && (
                    <Badge variant="outline">{t('translations.materialIncomplete')}</Badge>
                  )}
                </span>
              </span>
              <span className="flex gap-2">
                <Button variant="outline" size="sm" disabled={editing.stale} data-testid="translation-material-edit"
                  onClick={() => setOpen({ base, own: own ?? null, number: index + 1 })}>
                  {own ? <Pencil aria-hidden /> : <Plus aria-hidden />}
                  {own ? t('translations.editMaterial') : t('translations.addMaterial')}
                </Button>
                {own && (
                  <ConfirmDialog destructive title={t('translations.removeMaterialTitle', { number: index + 1 })}
                    description={t('translations.removeMaterialText')} confirmLabel={t('translations.remove')}
                    cancelLabel={t('common.cancel')} onConfirm={() => remove(own).then(() => undefined)}
                    trigger={
                      <Button variant="outline" size="sm" disabled={editing.stale}
                        aria-label={t('translations.removeMaterialTitle', { number: index + 1 })}
                        data-testid="translation-material-remove">
                        <Trash2 aria-hidden />
                      </Button>
                    } />
                )}
              </span>
            </li>
          )
        })}
      </ol>
      {open && (
        <MaterialDialog material={open.own} number={open.number} open onOpenChange={(next) => !next && setOpen(null)}
          variant={{ base: open.base, language }} />
      )}
    </section>
  )
}

/**
 * The quiz's other languages (Q73a): which ones it has; for each, the texts side by side with the base's (missing
 * and out-of-date ones marked), the questions' and options' own images, and its own materials. The quiz's own texts
 * come from the draft; its questions a page at a time (F-S6c). The count of what a language still needs is the
 * draft's. Questions, options,
 * correct answers and points are the base's: there is nothing to change about them here.
 */
export function TranslationsPanel({ draft, generation }: { draft: Draft; generation: number }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const languages = draft.quiz.translationLanguages
  const [chosen, setChosen] = useState<ContentLanguage | null>(languages[0] ?? null)
  const language = chosen && languages.includes(chosen) ? chosen : languages[0] ?? null
  const texts = useMemo(() => sourceTexts(draft, TEXT_LIMITS), [draft])
  const hashes = useHashes(texts)
  const aiJob = useAiTranslationJob(draft.quiz.quizId).data ?? null
  const ready = aiJob?.status === 'READY' && aiJob.language === language ? aiJob : null
  const [onlyPending, setOnlyPending] = useState(false)
  // A page of questions with changes not saved yet holds the filter (it would read another page).
  const [held, setHeld] = useState(false)
  const id = useId()
  return (
    <div className="flex flex-col gap-6" data-testid="translations">
      <LanguagesForm draft={draft} />
      {language === null ? (
        <p className="text-muted-foreground text-sm" data-testid="translations-none">{t('translations.none')}</p>
      ) : (
        <>
          <SegmentedControl<ContentLanguage> value={language} onChange={setChosen}
            aria-label={t('translations.choose')} data-testid="translation-language"
            options={languages.map((l) => {
              const left = hashes ? pending(statusOf(draft, l, texts, hashes)) : null
              return { value: l, label: left === null ? tQb(`languages.${l}`)
                : left === 0 ? t('translations.languageDone', { language: tQb(`languages.${l}`) })
                  : t('translations.languagePending', { language: tQb(`languages.${l}`), count: left }) }
            })} />
          <AiTranslatePanel language={language} job={aiJob} />
          <MaterialsSection draft={draft} language={language} />
          <div className="flex items-center gap-2">
            <Checkbox id={`${id}-pending`} checked={onlyPending} disabled={held}
              data-testid="translation-only-pending" onCheckedChange={(checked) => setOnlyPending(checked === true)} />
            <Label htmlFor={`${id}-pending`} className="font-normal">{t('translations.onlyPending')}</Label>
          </div>
          {/* A save or a reload starts the texts again from what the server has. */}
          <QuizTextsSection key={`${language}-${generation}-${draft.quiz.revision}-${ready?.aiJobId ?? ''}`}
            draft={draft} language={language} texts={texts} hashes={hashes} ai={ready} onlyPending={onlyPending} />
          <QuestionsSection key={`${language}-${generation}`} language={language} ai={ready}
            onlyPending={onlyPending} onHeld={setHeld} />
          <CoverSection draft={draft} language={language} />
        </>
      )}
    </div>
  )
}
