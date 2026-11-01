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
import { useMutation } from '@tanstack/react-query'
import { Pencil, Plus, Save, Trash2 } from 'lucide-react'
import { useEffect, useId, useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { CONTENT_LANGUAGES, type ContentLanguage } from '../qb/api'
import { QB } from '../qb/messages'
import { fieldProblem } from '../qb/problems'
import {
  deleteMaterial,
  saveTranslations,
  setLanguages,
  type Draft,
  type Material,
  type TranslationEntry,
} from './api'
import { ContentImage } from './ContentImage'
import { FileField, formProblem, ProblemList, useEditing } from './editing'
import {
  hashesOf,
  materialComplete,
  optionImageKey,
  pending,
  questionImageKey,
  sourceTexts,
  statusOf,
  textState,
  translationsIn,
  variantOf,
  type Hashes,
  type SourceText,
  type TextState,
} from './languages'
import { LIMITS } from './limits'
import { MaterialDialog, useKindLabel } from './MaterialsPanel'

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

/** The texts of the quiz side by side with their translations in one language, saved together. */
function TextsForm({ draft, language, texts, hashes }: {
  draft: Draft
  language: ContentLanguage
  texts: SourceText[]
  hashes: Hashes | null
}) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const id = useId()
  const label = useSourceLabel()
  const saved = useMemo(() => translationsIn(draft, language), [draft, language])
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(texts.map((s) => [s.key, saved.get(s.key)?.text ?? ''])))
  const [onlyPending, setOnlyPending] = useState(false)
  const changed = texts.filter((s) => (values[s.key] ?? '') !== (saved.get(s.key)?.text ?? ''))
  const save = useMutation({
    mutationFn: () => saveTranslations(editing.quizId, language,
      changed.map((s): TranslationEntry => ({ key: s.key, text: (values[s.key] ?? '').trim() === '' ? null
        : values[s.key] })), editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('translations.saved'))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const shown = texts.filter((s) => s.text && s.text.trim() !== '').filter((s) => !onlyPending || !hashes
    || textState(s, saved.get(s.key), hashes) !== 'done')
  return (
    <section className="flex flex-col gap-4" aria-labelledby={`${id}-title`} data-testid="translation-texts">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.texts')}</h3>
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-pending`} checked={onlyPending} data-testid="translation-only-pending"
            onCheckedChange={(checked) => setOnlyPending(checked === true)} />
          <Label htmlFor={`${id}-pending`} className="font-normal">{t('translations.onlyPending')}</Label>
        </div>
      </div>
      {shown.length === 0 && <p className="text-muted-foreground text-sm">{t('translations.nothingPending')}</p>}
      <ol className="flex flex-col gap-3">
        {shown.map((source) => {
          const state = hashes ? textState(source, saved.get(source.key), hashes) : null
          const fieldId = `${id}-${source.key}`
          const problem = fieldProblem(save.error, `entries[${changed.indexOf(source)}].text`)
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
              </div>
            </li>
          )
        })}
      </ol>
      <ProblemList problems={formProblem(save.error, changed.map((_, i) => `entries[${i}].text`))}
        testId="translation-error" />
      <Button className="w-fit" disabled={changed.length === 0 || save.isPending || editing.stale}
        aria-busy={save.isPending || undefined} data-testid="translation-save" onClick={() => save.mutate()}>
        <Save aria-hidden />
        {t('translations.save', { count: changed.length })}
      </Button>
    </section>
  )
}

/** A question's or an option's image in one language: the base's unless the language has its own. */
function ImagesSection({ draft, language }: { draft: Draft; language: ContentLanguage }) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const id = useId()
  const saved = translationsIn(draft, language)
  const save = useMutation({
    mutationFn: (entry: TranslationEntry) => saveTranslations(editing.quizId, language, [entry],
      editing.revision()),
    onSuccess: async (output) => {
      notify.success(t('translations.imageSaved'))
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const images = draft.questions.flatMap((q, i) => [
    ...(q.image ? [{ key: questionImageKey(q.questionId), base: q.image,
      what: t('translations.questionImage', { n: i + 1 }) }] : []),
    ...q.options.flatMap((o, j) => o.image ? [{ key: optionImageKey(o.optionId), base: o.image,
      what: t('translations.optionImage', { n: i + 1, m: j + 1 }) }] : []),
  ])
  if (images.length === 0) return null
  return (
    <section className="flex flex-col gap-3" aria-labelledby={`${id}-title`} data-testid="translation-images">
      <h3 id={`${id}-title`} className="text-base font-semibold">{t('translations.images')}</h3>
      <p className="text-muted-foreground text-sm">{t('translations.imagesHint')}</p>
      <ol className="flex flex-col gap-3">
        {images.map((image) => (
          <li key={image.key} className="flex flex-wrap items-start gap-4 rounded-md border p-3"
            data-testid="translation-image" data-key={image.key}>
            <div className="flex flex-col gap-1">
              <span className="text-sm font-medium">{image.what}</span>
              <ContentImage fileId={image.base} width={120} />
            </div>
            <FileField kind="image" label={t('translations.ownImage')}
              uploadLabel={saved.get(image.key)?.image ? t('translations.replaceImage') : t('translations.uploadImage')}
              value={saved.get(image.key)?.image ?? null} disabled={save.isPending || editing.stale}
              testId="translation-image-file" onChange={(fileId) => save.mutate({ key: image.key, image: fileId })} />
          </li>
        ))}
      </ol>
      <ProblemList problems={formProblem(save.error, [])} />
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
 * and out-of-date ones marked), the questions' and options' own images, and its own materials. Questions, options,
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
          <MaterialsSection draft={draft} language={language} />
          {/* A save or a reload starts the texts again from what the server has. */}
          <TextsForm key={`${language}-${generation}-${draft.quiz.revision}`} draft={draft} language={language}
            texts={texts} hashes={hashes} />
          <ImagesSection draft={draft} language={language} />
        </>
      )}
    </div>
  )
}
