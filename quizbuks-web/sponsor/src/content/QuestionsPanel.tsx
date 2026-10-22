import {
  Badge,
  Button,
  Card,
  CardContent,
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
  Textarea,
} from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Check, Pencil, Plus, Trash2, X } from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { fieldProblem } from '../qb/problems'
import { deleteQuestion, moved, reorderQuestions, saveQuestion, type Question, type Selection } from './api'
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
import { useMoveFocus } from './moving'

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
function QuestionDialog({ question, number, survey, open, onOpenChange }: {
  /** The question to change; a new one at the end when null. */
  question: Question | null
  /** Its place, from 1. */
  number: number
  survey: boolean
  open: boolean
  onOpenChange: (open: boolean) => void
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

  const save = useMutation({
    mutationFn: () => saveQuestion({
      quizId: editing.quizId,
      questionId: question?.questionId ?? null,
      stem: textOrNull(stem),
      image,
      points: survey ? 1 : wholeOrNull(points) ?? 1,
      options: options.map((o) => ({ optionId: o.optionId, text: textOrNull(o.text), image: o.image,
        correct: !survey && o.correct })),
      baseRevision: editing.revision(),
      ...(survey ? { selection } : {}),
    }),
    onSuccess: async (output) => {
      onOpenChange(false)
      notify.success(t('questions.saved', { number }))
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
              {t('questions.optionsHint', { min: LIMITS.minOptions, max: LIMITS.options })}
            </p>
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

/**
 * The questions of the quiz in their order (M-24): each with its options, the correct ones marked; added, changed,
 * moved up or down (`QB_QUESTION_REORDER` with the whole order) and deleted. A survey's (Q12) say single or multiple
 * choice instead of points and correct options.
 */
export function QuestionsPanel({ questions, survey = false }: { questions: Question[]; survey?: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const [editingQuestion, setEditingQuestion] = useState<{ question: Question | null; number: number } | null>(null)
  const addRef = useRef<HTMLButtonElement>(null)
  const reorder = useMutation({
    mutationFn: ({ ids }: { ids: string[]; id: string; to: number; by: -1 | 1 }) =>
      reorderQuestions(editing.quizId, ids, editing.revision()),
    onSuccess: async (output, { id, to, by }) => {
      await editing.edited(output)
      afterMove(id, by, t('questions.moved', { number: to + 1 }))
    },
    onError: editing.failed,
  })
  const { announcement, moved: afterMove } = useMoveFocus(reorder.isPending)
  const remove = (question: Question, number: number) =>
    deleteQuestion(editing.quizId, question.questionId, editing.revision()).then(
      async (output) => {
        notify.success(t('questions.deleted', { number }))
        await editing.edited(output)
      },
      (error: unknown) => failedRemoval(editing, error),
    )

  const move = (index: number, by: -1 | 1) => {
    const ids = moved(questions.map((q) => q.questionId), index, by)
    reorder.mutate({ ids, id: questions[index].questionId, to: index + by, by })
  }

  return (
    <div className="flex flex-col gap-4" data-testid="questions-panel">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-muted-foreground text-sm">{t('questions.hint', { max: LIMITS.questions })}</p>
        <Button ref={addRef} disabled={questions.length >= LIMITS.questions || editing.stale} data-testid="question-add"
          onClick={() => setEditingQuestion({ question: null, number: questions.length + 1 })}>
          <Plus aria-hidden />
          {t('questions.add')}
        </Button>
      </div>
      <p role="status" className="sr-only">{announcement}</p>
      <ProblemList problems={formProblem(reorder.error, [])} />
      {questions.length === 0
        ? <p className="text-muted-foreground text-sm" data-testid="questions-empty">{t('questions.none')}</p>
        : (
          <ol className="flex flex-col gap-3">
            {questions.map((question, index) => (
              <li key={question.questionId} data-testid="question" data-question={question.questionId}>
                <Card>
                  <CardContent className="flex flex-col gap-3">
                    <div className="flex flex-wrap items-start justify-between gap-2">
                      <h2 className="font-medium">{t('questions.number', { number: index + 1 })}</h2>
                      <Badge variant="outline" data-testid="question-badge">
                        {survey ? question.selection ? t(`questions.selections.${question.selection}`)
                          : t('questions.noSelection') : t('questions.pointsValue', { points: question.points })}
                      </Badge>
                    </div>
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
                    <div className="flex flex-wrap gap-2">
                      <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-edit"
                        aria-label={t('questions.edit', { number: index + 1 })}
                        onClick={() => setEditingQuestion({ question, number: index + 1 })}>
                        <Pencil aria-hidden />
                        {t('editor.edit')}
                      </Button>
                      <Button variant="outline" size="sm" disabled={index === 0 || reorder.isPending || editing.stale}
                        aria-label={t('questions.up', { number: index + 1 })} data-move={`${question.questionId}:-1`}
                        data-testid="question-up" onClick={() => move(index, -1)}>
                        <ArrowUp aria-hidden />
                      </Button>
                      <Button variant="outline" size="sm"
                        disabled={index === questions.length - 1 || reorder.isPending || editing.stale}
                        aria-label={t('questions.down', { number: index + 1 })} data-move={`${question.questionId}:1`}
                        data-testid="question-down" onClick={() => move(index, 1)}>
                        <ArrowDown aria-hidden />
                      </Button>
                      <ConfirmDialog destructive title={t('questions.deleteTitle', { number: index + 1 })}
                        description={t('questions.deleteText')} confirmLabel={t('editor.delete')}
                        cancelLabel={t('common.cancel')} returnFocus={() => addRef.current}
                        onConfirm={() => remove(question, index + 1)}
                        trigger={
                          <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-delete"
                            aria-label={t('questions.delete', { number: index + 1 })}>
                            <Trash2 aria-hidden />
                            {t('editor.delete')}
                          </Button>
                        } />
                    </div>
                  </CardContent>
                </Card>
              </li>
            ))}
          </ol>
        )}
      {editingQuestion && (
        <QuestionDialog key={editingQuestion.question?.questionId ?? 'new'} question={editingQuestion.question}
          number={editingQuestion.number} survey={survey} open onOpenChange={(open) => {
            if (!open) setEditingQuestion(null)
          }} />
      )}
    </div>
  )
}
