import { Button, Input, Label } from '@jabiz/ui'
import { useMutation } from '@tanstack/react-query'
import { CircleCheck } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { ShownAt } from './ShownAt'
import { fieldProblem } from '../qb/problems'
import { saveQuiz, type Quiz } from './api'
import { FileField, formProblem, MarkdownField, ProblemList, textOrNull, useEditing, wholeOrNull } from './editing'
import { LIMITS } from './limits'
import { QuizLanguageField } from './QuizLanguageField'
import type { ContentLanguage } from '../qb/api'

const FIELDS = ['title', 'intro', 'cover', 'timeLimitSec'] as const

/**
 * The quiz's heading (M-21, M-22): title, language (Q59a), introduction (Markdown), cover image and time limit, saved together with
 * `QB_QUIZ_SAVE`. A draft may be incomplete; only the title is required.
 */
export function HeadingForm({ quiz }: { quiz: Quiz }) {
  const { t } = useTranslation(SPONSOR)
  const editing = useEditing()
  const id = useId()
  const [title, setTitle] = useState(quiz.title)
  const [intro, setIntro] = useState(quiz.intro ?? '')
  const [cover, setCover] = useState<string | null>(quiz.cover)
  // A cover set elsewhere on the page (an AI cover chosen, Q8b) is shown here; the other fields keep their edits.
  const [readCover, setReadCover] = useState<string | null>(quiz.cover)
  if (quiz.cover !== readCover) {
    setReadCover(quiz.cover)
    setCover(quiz.cover)
  }
  const [timeLimit, setTimeLimit] = useState(quiz.timeLimitSec == null ? '' : String(quiz.timeLimitSec))
  const [language, setLanguage] = useState<ContentLanguage>(quiz.language)
  const [local, setLocal] = useState<string | null>(null)
  const [done, setDone] = useState(false)

  const save = useMutation({
    mutationFn: () => saveQuiz({
      quizId: quiz.quizId,
      title: title.trim(),
      intro: textOrNull(intro),
      cover,
      timeLimitSec: wholeOrNull(timeLimit),
      language,
      baseRevision: editing.revision(),
    }),
    onSuccess: async (output) => {
      setDone(true)
      await editing.edited(output)
    },
    onError: editing.failed,
  })

  const submit = (event: FormEvent) => {
    event.preventDefault()
    setDone(false)
    if (timeLimit.trim() !== '' && wholeOrNull(timeLimit) === null) {
      setLocal(t('editor.wholeNumber'))
      return
    }
    setLocal(null)
    save.mutate()
  }

  const problem = (field: string) => fieldProblem(save.error, field)
  const timeProblem = local ?? problem('timeLimitSec')
  return (
    <form noValidate onSubmit={submit} className="flex max-w-2xl flex-col gap-5" data-testid="heading-form"
      onChange={() => setDone(false)}>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-title`}>{t('editor.quizTitle')}</Label>
        <Input id={`${id}-title`} value={title} required maxLength={LIMITS.title} autoComplete="off"
          aria-invalid={problem('title') ? true : undefined}
          aria-describedby={problem('title') ? `${id}-title-error` : undefined}
          data-testid="heading-title" onChange={(event) => setTitle(event.target.value)} />
        {problem('title') && <p id={`${id}-title-error`} className="text-destructive text-sm">{problem('title')}</p>}
        <ShownAt field="quizTitle" />
      </div>
      <QuizLanguageField value={language} onChange={(next) => {
        setDone(false)
        setLanguage(next)
      }} testId="heading-language" />
      <div className="flex flex-col gap-2">
        <MarkdownField label={t('editor.intro')} value={intro} onChange={setIntro} maxLength={LIMITS.intro}
          error={problem('intro')} testId="heading-intro" />
        <ShownAt field="intro" />
      </div>
      <div className="flex flex-col gap-2">
        <FileField kind="image" label={t('editor.cover')} uploadLabel={cover ? t('editor.replaceCover') : t('editor.uploadCover')}
          value={cover} onChange={setCover} error={problem('cover')} testId="heading-cover" />
        <ShownAt field="cover" />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-time`}>{t('editor.timeLimit')}</Label>
        <Input id={`${id}-time`} type="number" inputMode="numeric" className="w-40" value={timeLimit}
          min={LIMITS.minTimeLimitSec} max={LIMITS.maxTimeLimitSec} step={1}
          aria-invalid={timeProblem ? true : undefined}
          aria-describedby={[`${id}-time-hint`, timeProblem ? `${id}-time-error` : ''].join(' ').trim()}
          data-testid="heading-time-limit" onChange={(event) => setTimeLimit(event.target.value)} />
        <p id={`${id}-time-hint`} className="text-muted-foreground text-sm">
          {t('editor.timeLimitHint', { min: LIMITS.minTimeLimitSec, max: LIMITS.maxTimeLimitSec.toLocaleString() })}
        </p>
        {timeProblem && <p id={`${id}-time-error`} className="text-destructive text-sm">{timeProblem}</p>}
      </div>
      <ProblemList problems={formProblem(save.error, FIELDS)} testId="heading-error" />
      {done && !save.isPending && (
        <p role="status" className="text-success flex items-center gap-2 text-sm" data-testid="heading-saved">
          <CircleCheck aria-hidden className="size-4" />
          {t('editor.saved')}
        </p>
      )}
      <Button type="submit" className="self-start" disabled={save.isPending || editing.stale}
        aria-busy={save.isPending || undefined} data-testid="heading-save">
        {t('editor.saveHeading')}
      </Button>
    </form>
  )
}
