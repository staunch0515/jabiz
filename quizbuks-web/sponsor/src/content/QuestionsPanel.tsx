import {
  Badge,
  Button,
  Checkbox,
  ConfirmDialog,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  notify,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
} from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Check, Image as ImageIcon, Pencil, Plus, Trash2, X } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { fieldProblem } from '../qb/problems'
import { choiceOf, deleteQuestion, moved, reorderQuestions, saveQuestion, type Question, type Selection } from './api'
import { Disclosure } from './Disclosure'
import {
  failedRemoval,
  FileField,
  formProblem,
  newKey,
  ProblemList,
  textOrNull,
  useEditing,
  wholeOrNull,
} from './editing'
import { ContentImage } from './ContentImage'
import { LIMITS } from './limits'
import { useMoveToFocus } from './moving'
import type { ContentLanguage } from '../qb/api'
import { copiedAssetId, useAssetCopy, withAssets, type AssetCopy } from '../assets/copy'
import { LibraryActions } from '../assets/LibraryActions'

interface OptionDraft {
  key: string
  optionId: string | null
  text: string
  image: string | null
  correct: boolean
}

const blankOption = (): OptionDraft => ({ key: newKey(), optionId: null, text: '', image: null, correct: false })

const FIELDS = ['stem', 'image', 'points', 'options', 'selection'] as const

/**
 * One question with all its options (M-24), saved at once with `QB_QUESTION_SAVE` (the options are the whole list in
 * their order). A draft may be incomplete: two to six options with at least one correct are checked when a version
 * is saved. A survey's question (Q12) has no points and no correct option: it is single or multiple choice.
 */
function QuestionDialog({ question, number, survey, open, onOpenChange, onSaved, language = 'ja' }: {
  /** The question to change; a new one at the end when null. */
  question: Question | null
  /** Its place, from 1. */
  number: number
  survey: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
  /** The saved question's id (a new one's from the output), to show it open. */
  onSaved?: (questionId: string | null) => void
  /** The quiz's language: an image saved to the asset library is in it (F-C1). */
  language?: ContentLanguage
}) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const id = useId()
  const [stem, setStem] = useState(question?.stem ?? '')
  const [image, setImage] = useState<string | null>(question?.image ?? null)
  const [points, setPoints] = useState(String(question?.points ?? 1))
  const [selection, setSelection] = useState<Selection>(question?.selection ?? 'SINGLE')
  const [options, setOptions] = useState<OptionDraft[]>(() => question
    ? question.options.map((o) => ({ key: o.optionId, optionId: o.optionId, text: o.text ?? '', image: o.image,
      correct: o.correct }))
    : [blankOption(), blankOption()])
  const [local, setLocal] = useState<string | null>(null)
  const optionsRef = useRef<HTMLOListElement>(null)
  // F-C1: the question's image and each option's (by the option's key, which stays while the dialog is open, a new
  // option's too) chosen from the asset library name their asset with the save while unchanged. The dialog closes on
  // saving, so the copies are recorded once.
  const imageCopy = useAssetCopy<string | null>()
  const [optionCopies, setOptionCopies] = useState<Record<string, AssetCopy<string | null>>>({})
  const choseOption = (key: string, assetId: string, value: string | null) =>
    setOptionCopies((copies) => ({ ...copies, [key]: { assetId, value } }))
  const imageName = (n?: number) => (n === undefined ? t('questions.image') : t('questions.optionImage', { number: n }))
  const storeImage = (value: string | null, n?: number) => (value ? { kind: 'IMAGE' as const,
    name: (stem.trim() ? `${stem.trim().slice(0, 80)} · ${imageName(n)}` : imageName(n)), language,
    content: { image: value } } : null)

  const save = useMutation({
    mutationFn: () => saveQuestion({
      quizId: editing.quizId,
      questionId: question?.questionId ?? null,
      stem: textOrNull(stem),
      image,
      points: survey ? 1 : wholeOrNull(points) ?? 1,
      options: options.map((o) => ({ optionId: o.optionId, text: textOrNull(o.text), image: o.image,
        correct: !survey && o.correct,
        ...withAssets({ imageAssetId: copiedAssetId(optionCopies[o.key] ?? null, o.image) }) })),
      ...withAssets({ imageAssetId: imageCopy.idFor(image) }),
      baseRevision: editing.revision(),
      ...(survey ? { selection } : {}),
    }),
    onSuccess: async (output) => {
      onOpenChange(false)
      notify.success(t('questions.saved', { number }))
      onSaved?.(question?.questionId ?? output.itemId)
      await editing.edited(output)
    },
    onError: editing.failed,
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const n = wholeOrNull(points)
    if (!survey && (n === null || n < LIMITS.minPoints || n > LIMITS.maxPoints)) {
      setLocal(t('questions.pointsRange', { min: LIMITS.minPoints, max: LIMITS.maxPoints.toLocaleString() }))
      return
    }
    setLocal(null)
    save.mutate()
  }

  const change = (key: string, patch: Partial<OptionDraft>) =>
    setOptions((list) => list.map((o) => (o.key === key ? { ...o, ...patch } : o)))
  const focusOption = (index: number) => requestAnimationFrame(() =>
    optionsRef.current?.querySelectorAll<HTMLInputElement>('input[data-option-text]')[index]?.focus())

  const problem = (field: string) => fieldProblem(save.error, field)
  const pointsProblem = local ?? problem('points')
  return (
    <Dialog open={open} onOpenChange={(next) => {
      if (!save.isPending) onOpenChange(next)
    }}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl" data-testid="question-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-5">
          <DialogHeader>
            <DialogTitle>
              {question ? t('questions.editTitle', { number }) : t('questions.newTitle', { number })}
            </DialogTitle>
            <DialogDescription>{survey ? t('questions.surveyDialogHint') : t('questions.dialogHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-stem`}>{t('questions.stem')}</Label>
            <Textarea id={`${id}-stem`} value={stem} rows={3} maxLength={LIMITS.stem}
              aria-invalid={problem('stem') ? true : undefined}
              aria-describedby={problem('stem') ? `${id}-stem-error` : undefined}
              data-testid="question-stem" onChange={(event) => setStem(event.target.value)} />
            {problem('stem') && <p id={`${id}-stem-error`} className="text-destructive text-sm">{problem('stem')}</p>}
          </div>
          <FileField kind="image" label={t('questions.image')}
            uploadLabel={image ? t('questions.replaceImage') : t('questions.uploadImage')}
            value={image} onChange={setImage} error={problem('image')} testId="question-image" />
          <LibraryActions target="QUESTION_IMAGE" disabled={editing.stale} testId="question-image-library"
            name={imageName()} store={storeImage(image)}
            onPick={(asset) => {
              setImage(asset.image)
              imageCopy.chose(asset.assetId, asset.image)
            }}
            onStored={(assetId) => imageCopy.chose(assetId, image)} />
          {survey ? (
            <fieldset className="flex flex-col gap-2">
              <legend className="mb-2 text-sm font-medium">{t('questions.selection')}</legend>
              <RadioGroup value={selection} onValueChange={(next) => setSelection(next as Selection)}
                className="flex flex-wrap gap-4" data-testid="question-selection">
                {(['SINGLE', 'MULTIPLE'] as const).map((value) => (
                  <div key={value} className="flex items-center gap-2">
                    <RadioGroupItem id={`${id}-${value}`} value={value} data-testid={`question-selection-${value}`} />
                    <Label htmlFor={`${id}-${value}`}>{t(`questions.selections.${value}`)}</Label>
                  </div>
                ))}
              </RadioGroup>
            </fieldset>
          ) : (
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-points`}>{t('questions.points')}</Label>
            <Input id={`${id}-points`} type="number" inputMode="numeric" className="w-32" value={points}
              min={LIMITS.minPoints} max={LIMITS.maxPoints} step={1} required
              aria-invalid={pointsProblem ? true : undefined}
              aria-describedby={pointsProblem ? `${id}-points-error` : undefined}
              data-testid="question-points" onChange={(event) => setPoints(event.target.value)} />
            {pointsProblem && <p id={`${id}-points-error`} className="text-destructive text-sm">{pointsProblem}</p>}
          </div>
          )}
          <fieldset className="flex flex-col gap-3">
            <legend className="mb-2 text-sm font-medium">{t('questions.options')}</legend>
            <p className="text-muted-foreground text-sm">
              {t(survey ? 'questions.surveyOptionsHint' : 'questions.optionsHint', { min: LIMITS.minOptions, max: LIMITS.options })}
            </p>
            {!survey && <ChoiceHint correct={options.filter((o) => o.correct).length} />}
            <ol ref={optionsRef} className="flex flex-col gap-3">
              {options.map((option, index) => (
                <li key={option.key} className="flex flex-col gap-3 rounded-md border p-3" data-testid="option">
                  <div className="flex flex-col gap-2">
                    <Label htmlFor={`${id}-${option.key}-text`}>{t('questions.option', { number: index + 1 })}</Label>
                    <Input id={`${id}-${option.key}-text`} value={option.text} maxLength={LIMITS.option}
                      autoComplete="off" data-option-text data-testid="option-text"
                      onChange={(event) => change(option.key, { text: event.target.value })} />
                  </div>
                  {!survey && (
                    <div className="flex items-center gap-2">
                      <Checkbox id={`${id}-${option.key}-correct`} checked={option.correct} data-testid="option-correct"
                        onCheckedChange={(checked) => change(option.key, { correct: checked === true })} />
                      <Label htmlFor={`${id}-${option.key}-correct`}>{t('questions.correct')}</Label>
                    </div>
                  )}
                  <FileField kind="image" label={t('questions.optionImage', { number: index + 1 })}
                    uploadLabel={option.image ? t('questions.replaceOptionImage', { number: index + 1 })
                      : t('questions.uploadOptionImage', { number: index + 1 })}
                    value={option.image} onChange={(fileId) => change(option.key, { image: fileId })}
                    testId="option-image" />
                  <LibraryActions target="OPTION_IMAGE" disabled={editing.stale} testId="option-image-library"
                    name={imageName(index + 1)} store={storeImage(option.image, index + 1)}
                    onPick={(asset) => {
                      change(option.key, { image: asset.image })
                      choseOption(option.key, asset.assetId, asset.image)
                    }}
                    onStored={(assetId) => choseOption(option.key, assetId, option.image)} />
                  <div className="flex flex-wrap gap-2">
                    <Button type="button" variant="outline" size="sm" disabled={index === 0}
                      aria-label={t('questions.optionUp', { number: index + 1 })}
                      onClick={() => {
                        setOptions((list) => moved(list, index, -1))
                        focusOption(index - 1)
                      }}>
                      <ArrowUp aria-hidden />
                    </Button>
                    <Button type="button" variant="outline" size="sm" disabled={index === options.length - 1}
                      aria-label={t('questions.optionDown', { number: index + 1 })}
                      onClick={() => {
                        setOptions((list) => moved(list, index, 1))
                        focusOption(index + 1)
                      }}>
                      <ArrowDown aria-hidden />
                    </Button>
                    <Button type="button" variant="outline" size="sm" data-testid="option-remove"
                      aria-label={t('questions.optionRemove', { number: index + 1 })}
                      onClick={() => {
                        setOptions((list) => list.filter((o) => o.key !== option.key))
                        focusOption(Math.max(0, index - 1))
                      }}>
                      <X aria-hidden />
                      {t('editor.remove')}
                    </Button>
                  </div>
                </li>
              ))}
            </ol>
            <Button type="button" variant="outline" className="self-start" disabled={options.length >= LIMITS.options}
              data-testid="option-add" onClick={() => {
                setOptions((list) => [...list, blankOption()])
                focusOption(options.length)
              }}>
              <Plus aria-hidden />
              {t('questions.addOption')}
            </Button>
          </fieldset>
          <ProblemList problems={formProblem(save.error, FIELDS)} testId="question-error" />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={save.isPending} onClick={() => onOpenChange(false)}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={save.isPending || editing.stale} aria-busy={save.isPending || undefined}
              data-testid="question-save">
              {t('questions.save')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** A scored question's badge: single or multiple choice, and its points. */
export function useScoredBadge(): (correct: number, points: number) => string {
  const { t } = useTranslation(SPONSOR)
  return (correct, points) => {
    const choice = choiceOf(correct)
    const value = t('questions.pointsValue', { points })
    return choice ? `${t(`questions.selections.${choice}`)} · ${value}` : value
  }
}

/** Under a scored question's options: single or multiple choice as the correct options marked make it. */
function ChoiceHint({ correct }: { correct: number }) {
  const { t } = useTranslation(SPONSOR)
  const choice = choiceOf(correct)
  return (
    <p className="text-sm font-medium" data-testid="question-choice" data-choice={choice ?? 'none'} aria-live="polite">
      {choice ? t(`questions.choiceHint.${choice}`, { count: correct }) : t('questions.choiceHint.none')}
    </p>
  )
}

/** Q78: the summary line over the list: how many, the full score, how many of each choice, how many unmarked. */
function QuestionsSummary({ questions, survey, onUnmarked }: { questions: Question[]; survey: boolean;
  onUnmarked: (questionId: string) => void }) {
  const { t } = useTranslation(SPONSOR)
  const correctOf = (q: Question) => q.options.filter((o) => o.correct).length
  const single = questions.filter((q) => choiceOf(correctOf(q)) === 'SINGLE').length
  const multiple = questions.filter((q) => choiceOf(correctOf(q)) === 'MULTIPLE').length
  const unmarked = questions.filter((q) => correctOf(q) === 0)
  const score = questions.reduce((sum, q) => sum + q.points, 0)
  return (
    <p className="text-muted-foreground flex flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-testid="questions-summary">
      <span data-testid="questions-count">{t('questions.summary.count', { count: questions.length, max: LIMITS.questions })}</span>
      {!survey && <span data-testid="questions-score">{t('questions.summary.score', { score })}</span>}
      {!survey && <span>{t('questions.summary.choices', { single, multiple })}</span>}
      {!survey && unmarked.length > 0 && (
        <Button type="button" variant="link" size="sm" className="text-destructive h-auto p-0"
          data-testid="questions-unmarked" onClick={() => onUnmarked(unmarked[0].questionId)}>
          {t('questions.summary.unmarked', { count: unmarked.length })}
        </Button>
      )}
    </p>
  )
}

/**
 * The questions of the quiz in their order (M-24): each a row (Q78: number, first line, points, single or multiple
 * choice, option count) that opens into its options with the correct ones marked; added, changed, moved to any place
 * (`QB_QUESTION_REORDER` with the whole order) and deleted from the row. A survey's (Q12) say single or multiple
 * choice instead of points and correct options. One question is open at a time; the one just added, changed or
 * moved opens.
 */
export function QuestionsPanel({ questions, survey = false, language }: { questions: Question[]; survey?: boolean
  /** The quiz's language (an image saved to the asset library is in it, F-C1). */
  language?: ContentLanguage }) {
  const { t } = useTranslation(SPONSOR)
  const scoredBadge = useScoredBadge()
  const editing = useEditing()
  const [editingQuestion, setEditingQuestion] = useState<{ question: Question | null; number: number } | null>(null)
  const [chosen, setExpanded] = useState<string | null>(null)
  // A question deleted (here or elsewhere) is not open.
  const expanded = chosen && questions.some((q) => q.questionId === chosen) ? chosen : null
  const addRef = useRef<HTMLButtonElement>(null)
  const rowRefs = useRef(new Map<string, HTMLLIElement>())
  const reorder = useMutation({
    mutationFn: ({ ids }: { ids: string[]; id: string; to: number }) =>
      reorderQuestions(editing.quizId, ids, editing.revision()),
    onSuccess: async (output, { id, to }) => {
      await editing.edited(output)
      setExpanded(id)
      afterMove(id, t('questions.moved', { number: to + 1 }))
    },
    onError: editing.failed,
  })
  const { announcement, moved: afterMove } = useMoveToFocus(reorder.isPending)
  const remove = (question: Question, number: number) =>
    deleteQuestion(editing.quizId, question.questionId, editing.revision()).then(
      async (output) => {
        notify.success(t('questions.deleted', { number }))
        await editing.edited(output)
      },
      (error: unknown) => failedRemoval(editing, error),
    )

  const moveTo = (index: number, to: number) => {
    if (to === index) return
    const ids = questions.map((q) => q.questionId)
    const [id] = ids.splice(index, 1)
    ids.splice(to, 0, id)
    reorder.mutate({ ids, id, to })
  }
  const open = (questionId: string) => {
    setExpanded(questionId)
    requestAnimationFrame(() => rowRefs.current.get(questionId)?.scrollIntoView?.({ block: 'nearest' }))
  }

  return (
    <div className="flex flex-col gap-4" data-testid="questions-panel">
      <div className="bg-background sticky top-0 z-10 flex flex-col gap-2 border-b py-2" data-testid="questions-toolbar">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-muted-foreground text-sm">{t('questions.hint', { max: LIMITS.questions })}</p>
          <Button ref={addRef} disabled={questions.length >= LIMITS.questions || editing.stale} data-testid="question-add"
            onClick={() => setEditingQuestion({ question: null, number: questions.length + 1 })}>
            <Plus aria-hidden />
            {t('questions.add')}
          </Button>
        </div>
        {questions.length > 0 && <QuestionsSummary questions={questions} survey={survey} onUnmarked={open} />}
      </div>
      <p role="status" className="sr-only">{announcement}</p>
      <ProblemList problems={formProblem(reorder.error, [])} />
      {questions.length === 0
        ? <p className="text-muted-foreground text-sm" data-testid="questions-empty">{t('questions.none')}</p>
        : (
          <ol className="flex flex-col gap-2">
            {questions.map((question, index) => {
              const correct = question.options.filter((o) => o.correct).length
              const badge = survey ? question.selection ? t(`questions.selections.${question.selection}`)
                : t('questions.noSelection')
                : scoredBadge(correct, question.points)
              const isOpen = expanded === question.questionId
              return (
                <li key={question.questionId} data-testid="question" data-question={question.questionId}
                  ref={(node) => {
                    if (node) rowRefs.current.set(question.questionId, node)
                    else rowRefs.current.delete(question.questionId)
                  }}>
                  <Disclosure expanded={isOpen} testId="question-row"
                    label={t(isOpen ? 'questions.collapse' : 'questions.expand', { number: index + 1 })}
                    onToggle={() => setExpanded(isOpen ? null : question.questionId)}
                    summary={
                      <>
                        <span className="font-medium">{t('questions.number', { number: index + 1 })}</span>
                        <span className="text-foreground line-clamp-1 min-w-0 flex-1 basis-40" data-testid="question-first-line">
                          {question.stem?.split('\n')[0] ?? <span className="text-muted-foreground">{t('questions.noStem')}</span>}
                        </span>
                        <Badge variant="outline" data-testid="question-badge">{badge}</Badge>
                        <span className="text-muted-foreground text-xs" data-testid="question-option-count">
                          {t('questions.optionCount', { count: question.options.length })}
                        </span>
                        {!survey && correct === 0 && question.options.length > 0 && (
                          <Badge variant="destructive" data-testid="question-unmarked">{t('questions.unmarked')}</Badge>
                        )}
                        {(question.image || question.options.some((o) => o.image)) && (
                          <ImageIcon aria-label={t('questions.hasImage')} className="text-muted-foreground size-4" />
                        )}
                      </>
                    }
                    actions={
                      <>
                        <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-edit"
                          aria-label={t('questions.edit', { number: index + 1 })}
                          onClick={() => setEditingQuestion({ question, number: index + 1 })}>
                          <Pencil aria-hidden />
                          {t('editor.edit')}
                        </Button>
                        <Select value={String(index)} disabled={reorder.isPending || editing.stale || questions.length < 2}
                          onValueChange={(value) => {
                            if (value) moveTo(index, Number(value))
                          }}>
                          <SelectTrigger size="sm" className="w-auto" aria-label={t('questions.moveTo', { number: index + 1 })}
                            data-move={question.questionId} data-testid="question-move">
                            <SelectValue />
                          </SelectTrigger>
                          <SelectContent>
                            {questions.map((_, place) => (
                              <SelectItem key={place} value={String(place)} data-testid="question-move-place">
                                {t('questions.place', { number: place + 1 })}
                              </SelectItem>
                            ))}
                          </SelectContent>
                        </Select>
                        <ConfirmDialog destructive title={t('questions.deleteTitle', { number: index + 1 })}
                          description={t('questions.deleteText')} confirmLabel={t('editor.delete')}
                          cancelLabel={t('common.cancel')} returnFocus={() => addRef.current}
                          onConfirm={() => remove(question, index + 1)}
                          trigger={
                            <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-delete"
                              aria-label={t('questions.delete', { number: index + 1 })}>
                              <Trash2 aria-hidden />
                              <span className="sr-only sm:not-sr-only">{t('editor.delete')}</span>
                            </Button>
                          } />
                      </>
                    }>
                    <div className="flex flex-col gap-3">
                      <p className="whitespace-pre-wrap" data-testid="question-stem-text">
                        {question.stem ?? <span className="text-muted-foreground">{t('questions.noStem')}</span>}
                      </p>
                      {question.image && <ContentImage fileId={question.image} width={160} />}
                      {question.options.length === 0
                        ? <p className="text-muted-foreground text-sm">{t('questions.noOptions')}</p>
                        : (
                          <ol className="flex flex-col gap-1 text-sm">
                            {question.options.map((option, n) => (
                              <li key={option.optionId} className="flex items-center gap-2" data-testid="option-row"
                                data-correct={option.correct}>
                                {option.correct
                                  ? <Check aria-hidden className="text-success size-4 shrink-0" />
                                  : <span aria-hidden className="size-4 shrink-0" />}
                                <span>{n + 1}.</span>
                                <span>{option.text ?? (option.image ? t('questions.imageOnly') : t('questions.emptyOption'))}</span>
                                {option.correct && <span className="sr-only">{t('questions.correctMark')}</span>}
                                {option.image && <ContentImage fileId={option.image} width={64} />}
                              </li>
                            ))}
                          </ol>
                        )}
                    </div>
                  </Disclosure>
                </li>
              )
            })}
          </ol>
        )}
      {editingQuestion && (
        <QuestionDialog key={editingQuestion.question?.questionId ?? 'new'} question={editingQuestion.question}
          number={editingQuestion.number} survey={survey} language={language} open onSaved={(id) => {
            if (id) open(id)
          }} onOpenChange={(open) => {
            if (!open) setEditingQuestion(null)
          }} />
      )}
    </div>
  )
}
