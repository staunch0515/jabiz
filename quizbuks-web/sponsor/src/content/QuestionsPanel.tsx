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
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowDown, ArrowUp, Check, ChevronLeft, ChevronRight, Image as ImageIcon, Pencil, Plus, Search, Trash2, X,
} from 'lucide-react'
import { useId, useRef, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { QB } from '../qb/messages'
import { fieldProblem } from '../qb/problems'
import { Link } from '../qb/router'
import {
  choiceOf,
  deleteQuestion,
  deleteQuestions,
  MAX_BATCH,
  moved,
  moveQuestion,
  questionAt,
  readBankLimits,
  readQuestion,
  readQuestionSummary,
  saveQuestion,
  searchQuestions,
  setQuestionPoints,
  type Question,
  type QuestionSummary,
  type QuestionRow,
  type Selection,
} from './api'
import { ImportBatches, ImportQuestionsButton } from './BankImport'
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
import { LIMITS, MOVE_LIST_MAX, QUESTION_PAGE } from './limits'
import { useMoveFocus, useMoveToFocus } from './moving'
import { BANK_LIMITS_QUERY, QUIZZES_QUERY } from './paths'
import type { ContentLanguage } from '../qb/api'
import { copiedAssetId, useAssetCopy, withAssets, type AssetCopy } from '../assets/copy'
import { LibraryActions } from '../assets/LibraryActions'
import { DifficultyDialog, DifficultyField, DifficultyFilter, setQuestionDifficulty, type Difficulty } from './Difficulty'

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
  // F-S7: a scored question's difficulty (a survey's questions are never drawn by it).
  const [difficulty, setDifficulty] = useState<Difficulty | null>(question?.difficulty ?? null)
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
      ...(survey ? { selection } : { difficulty: difficulty ?? '' }),
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
          {!survey && <DifficultyField value={difficulty} onChange={setDifficulty} />}
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

/**
 * Q78: the summary line over the list: how many, the full score, how many of each choice, how many unmarked (F-S6d:
 * counted by the server, `qb.sponsor.quiz-question-summary`, so the editor reads no question for it).
 */
function QuestionsSummary({ summary, count, max, survey, onUnmarked }: { summary: QuestionSummary | undefined
  /** The quiz's questions (F-S6: counted by the server, not by what the page holds). */
  count: number
  /** The bank's limit (F-S6), once read. */
  max: number | undefined
  survey: boolean
  onUnmarked: (questionId: string, no: number) => void }) {
  const { t } = useTranslation(SPONSOR)
  const first = summary?.firstUnmarked
  return (
    <p className="text-muted-foreground flex flex-wrap items-center gap-x-3 gap-y-1 text-sm" data-testid="questions-summary">
      <span data-testid="questions-count">
        {max ? t('questions.summary.count', { count, max: max.toLocaleString() }) : t('bank.countOnly', { count })}
      </span>
      {!survey && summary && (
        <span data-testid="questions-score">{t('questions.summary.score', { score: summary.fullScore })}</span>
      )}
      {!survey && summary && (
        <span>{t('questions.summary.choices', { single: summary.single, multiple: summary.multiple })}</span>
      )}
      {!survey && summary && summary.unmarked > 0 && first && (
        <Button type="button" variant="link" size="sm" className="text-destructive h-auto p-0"
          data-testid="questions-unmarked" onClick={() => onUnmarked(first.questionId, first.no)}>
          {t('questions.summary.unmarked', { count: summary.unmarked })}
        </Button>
      )}
    </p>
  )
}

/** F-S6b: "move to" a typed place, for a quiz too large to list every place. */
function MoveDialog({ number, max, onMove, onClose }: { number: number; max: number
  onMove: (to: number) => void; onClose: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [value, setValue] = useState(String(number))
  const [problem, setProblem] = useState<string | null>(null)
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent data-testid="question-move-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          const to = wholeOrNull(value)
          if (to === null || to < 1 || to > max) {
            setProblem(t('bank.moveRange', { max }))
            return
          }
          onClose()
          onMove(to)
        }}>
          <DialogHeader>
            <DialogTitle>{t('bank.moveTitle', { number })}</DialogTitle>
            <DialogDescription>{t('bank.moveHint', { max })}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-place`}>{t('bank.movePlace')}</Label>
            <Input id={`${id}-place`} type="number" inputMode="numeric" className="w-32" min={1} max={max} step={1}
              value={value} aria-invalid={problem ? true : undefined}
              aria-describedby={problem ? `${id}-error` : undefined} data-testid="question-move-place-input"
              onChange={(event) => setValue(event.target.value)} />
            {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" data-testid="question-move-confirm">{t('bank.moveSave')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** F-S6b: the same points for every selected question (`QB_QUESTION_POINTS_SET`). */
function PointsDialog({ count, onClose, onSave, error, pending }: { count: number; onClose: () => void
  onSave: (points: number) => void; error: unknown; pending: boolean }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [value, setValue] = useState('1')
  const [local, setLocal] = useState<string | null>(null)
  const problem = local ?? fieldProblem(error, 'points')
  return (
    <Dialog open onOpenChange={(open) => !open && !pending && onClose()}>
      <DialogContent data-testid="bank-points-dialog">
        <form noValidate className="flex flex-col gap-4" onSubmit={(event) => {
          event.preventDefault()
          const n = wholeOrNull(value)
          if (n === null || n < LIMITS.minPoints || n > LIMITS.maxPoints) {
            setLocal(t('questions.pointsRange', { min: LIMITS.minPoints, max: LIMITS.maxPoints.toLocaleString() }))
            return
          }
          setLocal(null)
          onSave(n)
        }}>
          <DialogHeader>
            <DialogTitle>{t('bank.pointsTitle', { count })}</DialogTitle>
            <DialogDescription>{t('bank.pointsHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-points`}>{t('questions.points')}</Label>
            <Input id={`${id}-points`} type="number" inputMode="numeric" className="w-32" value={value}
              min={LIMITS.minPoints} max={LIMITS.maxPoints} step={1} aria-invalid={problem ? true : undefined}
              aria-describedby={problem ? `${id}-error` : undefined} data-testid="bank-points-input"
              onChange={(event) => setValue(event.target.value)} />
            {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          <ProblemList problems={formProblem(error, ['points'])} />
          <DialogFooter>
            <Button type="button" variant="outline" disabled={pending} onClick={onClose}>{t('common.cancel')}</Button>
            <Button type="submit" disabled={pending} aria-busy={pending || undefined} data-testid="bank-points-save">
              {t('bank.pointsSave')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/** The key of one question read on its own (F-S6b), under the quizzes' key so that an edit reads it again. */
const questionQuery = (questionId: string) => [...QUIZZES_QUERY, 'question', questionId] as const

/** An open question's options, read for it alone when it opens (F-S6b; F-S6d: the editor's draft has none). */
function QuestionDetail({ row }: { row: QuestionRow }) {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const read = useQuery({ queryKey: questionQuery(row.questionId), queryFn: () => readQuestion(row.questionId) })
  const question = read.data ?? null
  if (!question) {
    return read.isError ? <p className="text-destructive text-sm">{tQb('failed')}</p>
      : <p role="status" className="text-muted-foreground text-sm">{t('bank.loading')}</p>
  }
  return (
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
  )
}

/**
 * The questions of the quiz in their order (M-24), a page at a time (F-S6b: `qb.sponsor.quiz-questions`, 50 a page,
 * searched by text or number): each a row (Q78: number, first line, points, single or multiple choice, option count)
 * that opens into its options with the correct ones marked; added, changed, moved and deleted from the row. A move is
 * one question to another place (`QB_QUESTION_MOVE`): up, down, or to any place. Several selected questions are
 * deleted or given points at once; a quiz file's questions are imported in batches, each of which can be undone. A
 * survey's (Q12) say single or multiple choice instead of points and correct options. One question is open at a
 * time; the one just added, changed or moved opens.
 */
export function QuestionsPanel({ survey = false, language, count: quizCount }: {
  survey?: boolean
  /** The quiz's language (an image saved to the asset library is in it, F-C1). */
  language?: ContentLanguage
  /** The quiz's questions as its row counts them, until the summary is read. */
  count: number }) {
  const { t } = useTranslation(SPONSOR)
  const scoredBadge = useScoredBadge()
  const editing = useEditing()
  const queryClient = useQueryClient()
  const searchId = useId()
  const [editingQuestion, setEditingQuestion] = useState<{ question: Question | null; number: number } | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [typed, setTyped] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<string[]>([])
  const [moving, setMoving] = useState<QuestionRow | null>(null)
  const [pointing, setPointing] = useState(false)
  // F-S7: the list's filter by difficulty, and the dialog setting the selected questions' difficulty.
  const [level, setLevel] = useState<Difficulty | 'NONE' | null>(null)
  const [grading, setGrading] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const addRef = useRef<HTMLButtonElement>(null)
  const rowRefs = useRef(new Map<string, HTMLLIElement>())
  const limits = useQuery({ queryKey: BANK_LIMITS_QUERY, queryFn: readBankLimits, staleTime: 60_000 })
  const list = useQuery({
    queryKey: [...QUIZZES_QUERY, 'questions', editing.quizId, search, page, level],
    queryFn: () => searchQuestions({ quizId: editing.quizId, search, offset: page * QUESTION_PAGE,
      limit: QUESTION_PAGE, difficulty: level }),
    placeholderData: keepPreviousData,
  })
  const rows = list.data?.items ?? []
  const total = list.data?.total ?? 0
  const pages = Math.max(1, Math.ceil(total / QUESTION_PAGE))
  // F-S6d: the summary line from the server; the editor holds no question of its own.
  const summary = useQuery({ queryKey: [...QUIZZES_QUERY, 'question-summary', editing.quizId],
    queryFn: () => readQuestionSummary(editing.quizId) })
  const count = summary.data?.questionCount ?? quizCount
  const max = limits.data?.maxQuestions
  const full = max !== undefined && count >= max

  // A page emptied by deletions shows the last one there is (adjusted while rendering, as React advises).
  if (list.data && !list.isPlaceholderData && rows.length === 0 && page > 0 && page > pages - 1) setPage(pages - 1)

  const move = useMutation({
    mutationFn: async ({ row, to }: { row: QuestionRow; to: number; by?: -1 | 1 }) => {
      // Before the question now at the place (going up) or after it (going down); past the last, at the end.
      const place = to < row.no ? to : to + 1
      const before = place > count ? null
        : rows.find((r) => r.no === place) ?? await questionAt(editing.quizId, place)
      return moveQuestion(editing.quizId, row.questionId, before?.questionId ?? null, editing.revision())
    },
    onSuccess: async (output, { row, to, by }) => {
      setExpanded(row.questionId)
      // Unsearched, the list follows the question to its new page.
      if (!search) setPage(Math.floor((to - 1) / QUESTION_PAGE))
      const text = t('questions.moved', { number: to })
      setAnnouncement(text)
      if (by) movedBy(row.questionId, by, text)
      else movedTo(row.questionId, text)
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  // The keyboard goes back to the moved question's control once its new page is read.
  const focusBusy = move.isPending || list.isFetching
  const { moved: movedBy } = useMoveFocus(focusBusy)
  const { moved: movedTo } = useMoveToFocus(focusBusy)

  const removeSelected = (ids: string[]) =>
    deleteQuestions(editing.quizId, ids, editing.revision()).then(
      async (output) => {
        notify.success(t('bank.deletedMany', { count: ids.length }))
        setSelected([])
        await editing.edited(output)
      },
      (error: unknown) => failedRemoval(editing, error),
    )
  const grade = useMutation({
    mutationFn: ({ ids, value }: { ids: string[]; value: Difficulty | '' }) =>
      setQuestionDifficulty(editing.quizId, ids, value, editing.revision()),
    onSuccess: async (output, { ids }) => {
      setGrading(false)
      notify.success(t('bank.difficultySet', { count: ids.length }))
      setSelected([])
      await editing.edited(output)
    },
    onError: editing.failed,
  })
  const points = useMutation({
    mutationFn: ({ ids, value }: { ids: string[]; value: number }) =>
      setQuestionPoints(editing.quizId, ids, value, editing.revision()),
    onSuccess: async (output, { ids }) => {
      setPointing(false)
      notify.success(t('bank.pointsSet', { count: ids.length }))
      setSelected([])
      await editing.edited(output)
    },
    onError: editing.failed,
  })

  const remove = (row: QuestionRow) =>
    deleteQuestion(editing.quizId, row.questionId, editing.revision()).then(
      async (output) => {
        notify.success(t('questions.deleted', { number: row.no }))
        setSelected((ids) => ids.filter((id) => id !== row.questionId))
        await editing.edited(output)
      },
      (error: unknown) => failedRemoval(editing, error),
    )

  /** Opens a question at its place (from 1): its page (unsearched), the row, scrolled to. */
  const open = (questionId: string, no: number) => {
    const index = no - 1
    if (index >= 0 && (search || level || Math.floor(index / QUESTION_PAGE) !== page)) {
      setLevel(null)
      setSearch('')
      setTyped('')
      setPage(Math.floor(index / QUESTION_PAGE))
    }
    setExpanded(questionId)
    requestAnimationFrame(() => rowRefs.current.get(questionId)?.scrollIntoView?.({ block: 'nearest' }))
  }
  const edit = async (row: QuestionRow) => {
    const question = await queryClient.fetchQuery({ queryKey: questionQuery(row.questionId), queryFn: () => readQuestion(row.questionId) })
    if (question) setEditingQuestion({ question, number: row.no })
  }
  const toggle = (questionId: string, on: boolean) => setSelected((ids) => on
    ? ids.includes(questionId) || ids.length >= MAX_BATCH ? ids : [...ids, questionId]
    : ids.filter((id) => id !== questionId))
  const pageIds = rows.map((r) => r.questionId)
  const pageSelected = pageIds.filter((id) => selected.includes(id)).length
  const busy = move.isPending || points.isPending

  return (
    <div className="flex flex-col gap-4" data-testid="questions-panel">
      <div className="bg-background sticky top-0 z-10 flex flex-col gap-2 border-b py-2" data-testid="questions-toolbar">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="text-muted-foreground flex flex-col gap-1 text-sm" data-testid="questions-hint">
            <p>{max ? t('questions.hint', { max: max.toLocaleString() }) : t('bank.limitsLoading')}</p>
            {limits.data && (
              <p data-testid="questions-draw-hint">
                {t('bank.drawHint', { perAttempt: limits.data.maxPerAttempt })}{' '}
                <Link to="/help" className="underline underline-offset-4">{t('bank.helpLink')}</Link>
              </p>
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            <ImportQuestionsButton limits={limits.data} count={count} />
            <Button ref={addRef} disabled={full || editing.stale} data-testid="question-add"
              onClick={() => setEditingQuestion({ question: null, number: count + 1 })}>
              <Plus aria-hidden />
              {t('questions.add')}
            </Button>
          </div>
        </div>
        {count > 0 && <QuestionsSummary summary={summary.data} count={count} max={max} survey={survey} onUnmarked={open} />}
      </div>
      <p role="status" className="sr-only">{announcement}</p>
      <ProblemList problems={formProblem(move.error, [])} />
      <ImportBatches />
      {count > 0 && (
        <form role="search" className="flex flex-wrap items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          setSearch(typed.trim())
          setPage(0)
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${searchId}-q`}>{t('bank.search')}</Label>
            <Input id={`${searchId}-q`} type="search" className="w-64" value={typed} maxLength={100}
              placeholder={t('bank.searchHint')} aria-describedby={`${searchId}-hint`} data-testid="questions-search"
              onChange={(event) => setTyped(event.target.value)} />
            <span id={`${searchId}-hint`} className="sr-only">{t('bank.searchHint')}</span>
          </div>
          <Button type="submit" variant="outline" data-testid="questions-search-submit">
            <Search aria-hidden />
            {t('bank.searchButton')}
          </Button>
          {!survey && (
            <DifficultyFilter value={level} onChange={(next) => {
              setLevel(next)
              setPage(0)
            }} />
          )}
          {search && (
            <Button type="button" variant="ghost" data-testid="questions-search-clear" onClick={() => {
              setTyped('')
              setSearch('')
              setPage(0)
            }}>
              {t('bank.clearSearch')}
            </Button>
          )}
          {(search || level) && list.data && (
            <span role="status" className="text-muted-foreground text-sm" data-testid="questions-found">
              {t('bank.found', { count: total })}
            </span>
          )}
        </form>
      )}
      {selected.length > 0 && (
        <div className="bg-muted/50 flex flex-wrap items-center gap-2 rounded-md border px-3 py-2 text-sm"
          role="region" aria-label={t('bank.selected', { count: selected.length })} data-testid="bank-selection">
          <span className="font-medium" data-testid="bank-selected">{t('bank.selected', { count: selected.length })}</span>
          {selected.length >= MAX_BATCH && (
            <span className="text-muted-foreground">{t('bank.selectionMax', { max: MAX_BATCH })}</span>
          )}
          <div className="ml-auto flex flex-wrap gap-2">
            {!survey && (
              <Button variant="outline" size="sm" disabled={busy || editing.stale} data-testid="bank-points"
                onClick={() => {
                  points.reset()
                  setPointing(true)
                }}>
                {t('bank.pointsSelected')}
              </Button>
            )}
            {!survey && (
              <Button variant="outline" size="sm" disabled={busy || grade.isPending || editing.stale}
                data-testid="bank-difficulty" onClick={() => {
                  grade.reset()
                  setGrading(true)
                }}>
                {t('bank.difficultySelected')}
              </Button>
            )}
            <ConfirmDialog destructive title={t('bank.deleteManyTitle', { count: selected.length })}
              description={t('bank.deleteManyText')} confirmLabel={t('editor.delete')} cancelLabel={t('common.cancel')}
              returnFocus={() => addRef.current} onConfirm={() => removeSelected(selected)}
              trigger={
                <Button variant="outline" size="sm" disabled={busy || editing.stale} data-testid="bank-delete">
                  <Trash2 aria-hidden />
                  {t('bank.deleteSelected')}
                </Button>
              } />
            <Button variant="ghost" size="sm" data-testid="bank-clear" onClick={() => setSelected([])}>
              {t('bank.clearSelection')}
            </Button>
          </div>
        </div>
      )}
      {count === 0 && !list.data?.items.length
        ? <p className="text-muted-foreground text-sm" data-testid="questions-empty">{t('questions.none')}</p>
        : (search || level) && list.data && rows.length === 0
          ? <p className="text-muted-foreground text-sm" data-testid="questions-no-match">{t('bank.noMatch')}</p>
          : (
            <>
              {rows.length > 1 && (
                <div className="flex items-center gap-2 px-1">
                  <Checkbox id={`${searchId}-all`} data-testid="bank-select-page"
                    checked={pageSelected === 0 ? false : pageSelected === pageIds.length ? true : 'indeterminate'}
                    onCheckedChange={(checked) => {
                      if (checked === true) {
                        setSelected((ids) => [...ids, ...pageIds.filter((id) => !ids.includes(id))].slice(0, MAX_BATCH))
                      } else setSelected((ids) => ids.filter((id) => !pageIds.includes(id)))
                    }} />
                  <Label htmlFor={`${searchId}-all`} className="text-sm font-normal">{t('bank.selectPage')}</Label>
                </div>
              )}
              <ol className="flex flex-col gap-2" data-testid="questions-list">
                {rows.map((row) => {
                  const correct = row.correctCount
                  const badge = survey ? row.selection ? t(`questions.selections.${row.selection}`)
                    : t('questions.noSelection')
                    : scoredBadge(correct, row.points)
                  const isOpen = expanded === row.questionId
                  const isSelected = selected.includes(row.questionId)
                  return (
                    <li key={row.questionId} className="flex items-start gap-2" data-testid="question"
                      data-question={row.questionId} data-no={row.no}
                      ref={(node) => {
                        if (node) rowRefs.current.set(row.questionId, node)
                        else rowRefs.current.delete(row.questionId)
                      }}>
                      <Checkbox className="mt-4" checked={isSelected} data-testid="question-select"
                        disabled={!isSelected && selected.length >= MAX_BATCH}
                        aria-label={t('bank.select', { number: row.no })}
                        onCheckedChange={(checked) => toggle(row.questionId, checked === true)} />
                      <div className="min-w-0 flex-1">
                        <Disclosure expanded={isOpen} testId="question-row"
                          label={t(isOpen ? 'questions.collapse' : 'questions.expand', { number: row.no })}
                          onToggle={() => setExpanded(isOpen ? null : row.questionId)}
                          summary={
                            <>
                              <span className="font-medium">{t('questions.number', { number: row.no })}</span>
                              <span className="text-foreground line-clamp-1 min-w-0 flex-1 basis-40" data-testid="question-first-line">
                                {row.stem?.split('\n')[0] ?? <span className="text-muted-foreground">{t('questions.noStem')}</span>}
                              </span>
                              <Badge variant="outline" data-testid="question-badge">{badge}</Badge>
                              {!survey && row.difficulty && (
                                <Badge variant="secondary" data-testid="question-difficulty-badge">
                                  {t(`difficulty.levels.${row.difficulty}`)}
                                </Badge>
                              )}
                              <span className="text-muted-foreground text-xs" data-testid="question-option-count">
                                {t('questions.optionCount', { count: row.optionCount })}
                              </span>
                              {!survey && correct === 0 && row.optionCount > 0 && (
                                <Badge variant="destructive" data-testid="question-unmarked">{t('questions.unmarked')}</Badge>
                              )}
                              {(row.image || row.optionImages) && (
                                <ImageIcon aria-label={t('questions.hasImage')} className="text-muted-foreground size-4" />
                              )}
                            </>
                          }
                          actions={
                            <>
                              <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-edit"
                                aria-label={t('questions.edit', { number: row.no })} onClick={() => void edit(row)}>
                                <Pencil aria-hidden />
                                {t('editor.edit')}
                              </Button>
                              <Button variant="outline" size="sm" disabled={busy || editing.stale || row.no <= 1}
                                aria-label={t('questions.up', { number: row.no })} data-move={`${row.questionId}:-1`}
                                data-testid="question-up" onClick={() => move.mutate({ row, to: row.no - 1, by: -1 })}>
                                <ArrowUp aria-hidden />
                              </Button>
                              <Button variant="outline" size="sm" disabled={busy || editing.stale || row.no >= count}
                                aria-label={t('questions.down', { number: row.no })} data-move={`${row.questionId}:1`}
                                data-testid="question-down" onClick={() => move.mutate({ row, to: row.no + 1, by: 1 })}>
                                <ArrowDown aria-hidden />
                              </Button>
                              {count <= MOVE_LIST_MAX ? (
                                <Select value={String(row.no)} disabled={busy || editing.stale || count < 2}
                                  onValueChange={(value) => {
                                    if (value && Number(value) !== row.no) move.mutate({ row, to: Number(value) })
                                  }}>
                                  <SelectTrigger size="sm" className="w-auto" aria-label={t('questions.moveTo', { number: row.no })}
                                    data-move={row.questionId} data-testid="question-move">
                                    <SelectValue />
                                  </SelectTrigger>
                                  <SelectContent>
                                    {Array.from({ length: count }, (_, place) => (
                                      <SelectItem key={place} value={String(place + 1)} data-testid="question-move-place">
                                        {t('questions.place', { number: place + 1 })}
                                      </SelectItem>
                                    ))}
                                  </SelectContent>
                                </Select>
                              ) : (
                                <Button variant="outline" size="sm" disabled={busy || editing.stale}
                                  aria-label={t('questions.moveTo', { number: row.no })} data-move={row.questionId}
                                  data-testid="question-move" onClick={() => setMoving(row)}>
                                  {t('bank.moveToButton')}
                                </Button>
                              )}
                              <ConfirmDialog destructive title={t('questions.deleteTitle', { number: row.no })}
                                description={t('questions.deleteText')} confirmLabel={t('editor.delete')}
                                cancelLabel={t('common.cancel')} returnFocus={() => addRef.current}
                                onConfirm={() => remove(row)}
                                trigger={
                                  <Button variant="outline" size="sm" disabled={editing.stale} data-testid="question-delete"
                                    aria-label={t('questions.delete', { number: row.no })}>
                                    <Trash2 aria-hidden />
                                    <span className="sr-only sm:not-sr-only">{t('editor.delete')}</span>
                                  </Button>
                                } />
                            </>
                          }>
                          <QuestionDetail row={row} />
                        </Disclosure>
                      </div>
                    </li>
                  )
                })}
              </ol>
              {pages > 1 && (
                <nav aria-label={t('bank.pages')} className="flex flex-wrap items-center justify-center gap-2"
                  data-testid="questions-pages">
                  <Button variant="outline" size="sm" disabled={page === 0} data-testid="questions-previous"
                    onClick={() => setPage((p) => Math.max(0, p - 1))}>
                    <ChevronLeft aria-hidden />
                    {t('bank.previous')}
                  </Button>
                  <span className="text-sm" aria-live="polite" data-testid="questions-page">
                    {t('bank.pageOf', { page: page + 1, pages })}
                  </span>
                  <Button variant="outline" size="sm" disabled={page + 1 >= pages} data-testid="questions-next"
                    onClick={() => setPage((p) => Math.min(pages - 1, p + 1))}>
                    {t('bank.next')}
                    <ChevronRight aria-hidden />
                  </Button>
                </nav>
              )}
            </>
          )}
      {editingQuestion && (
        <QuestionDialog key={editingQuestion.question?.questionId ?? 'new'} question={editingQuestion.question}
          number={editingQuestion.number} survey={survey} language={language} open onSaved={(id) => {
            if (!id) return
            if (editingQuestion.question === null) {
              // A new question is the last: its page, unsearched.
              setSearch('')
              setLevel(null)
              setTyped('')
              setPage(Math.floor(count / QUESTION_PAGE))
              setExpanded(id)
            } else open(id, editingQuestion.number)
          }} onOpenChange={(open) => {
            if (!open) setEditingQuestion(null)
          }} />
      )}
      {moving && (
        <MoveDialog number={moving.no} max={count} onClose={() => setMoving(null)}
          onMove={(to) => {
            if (to !== moving.no) move.mutate({ row: moving, to })
          }} />
      )}
      {grading && (
        <DifficultyDialog count={selected.length} error={grade.error} pending={grade.isPending}
          onClose={() => setGrading(false)} onSave={(value) => grade.mutate({ ids: selected, value })} />
      )}
      {pointing && (
        <PointsDialog count={selected.length} error={points.error} pending={points.isPending}
          onClose={() => setPointing(false)} onSave={(value) => points.mutate({ ids: selected, value })} />
      )}
    </div>
  )
}
