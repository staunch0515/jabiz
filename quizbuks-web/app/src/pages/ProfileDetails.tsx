import { useAuth } from '@jabiz/client'
import {
  Button,
  Checkbox,
  Input,
  Label,
  PageState,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CircleCheck } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { CRITERIA, readMyAnswers, saveMyAnswers, type Answer, type Criterion } from '../qb/audience'
import { QB } from '../qb/messages'
import { generalProblem, problemText } from '../qb/problems'

/** The query of the caller's own answers. */
export const ANSWERS_QUERY = ['qb', 'audience', 'answers'] as const

/** A single choice's "not told" (Radix's select takes no empty value). */
const NOT_SET = '-'

/**
 * What a taker tells of themself for matching (Q39, `QB_PROFILE_ATTRIBUTES_SAVE`): every criterion is optional, and
 * the more they tell, the more quizzes aimed at people like them they can answer. One control per criterion of
 * `qb/audience.ts`: a single choice, several (interests), or a birth year for an age. Each save sends all of it.
 */
export function ProfileDetails() {
  const { t } = useTranslation(QB)
  const answers = useQuery({ queryKey: ANSWERS_QUERY, queryFn: readMyAnswers })
  if (answers.isPending) return <PageState kind="loading" />
  if (answers.isError) return <PageState kind="error" description={t('failed')} />
  return <DetailsForm answers={answers.data} />
}

function DetailsForm({ answers }: { answers: Answer[] }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { emailVerified } = useAuth()
  const queryClient = useQueryClient()
  const id = useId()
  const [values, setValues] = useState<Record<string, Answer>>(
    () => Object.fromEntries(answers.map((answer) => [answer.key, answer])))
  const [birthYear, setBirthYear] = useState(() => {
    const number = answers.find((answer) => answer.key === 'age')?.number
    return number == null ? '' : String(number)
  })
  const [saved, setSaved] = useState(false)

  const save = useMutation({
    mutationFn: () => saveMyAnswers(CRITERIA.flatMap((criterion): Answer[] => {
      if (criterion.kind === 'RANGE') {
        const number = Number(birthYear.trim())
        return birthYear.trim() === '' || !Number.isInteger(number) ? [] : [{ key: criterion.key, number }]
      }
      const options = values[criterion.key]?.options ?? []
      return options.length === 0 ? [] : [{ key: criterion.key, options }]
    })),
    onSuccess: async () => {
      setSaved(true)
      // What the taker can answer follows what they told (Q39): the feed and the pages read it again.
      await Promise.all([ANSWERS_QUERY, ['qb', 'feed'], ['qb', 'publication']].map((queryKey) =>
        queryClient.invalidateQueries({ queryKey })))
    },
  })

  const choose = (criterion: Criterion, options: string[]) => {
    setSaved(false)
    setValues((current) => ({ ...current, [criterion.key]: { key: criterion.key, options } }))
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    setSaved(false)
    save.mutate()
  }
  // The server's problems name the answers by position; the page shows them all together.
  const problem = save.isError ? (generalProblem(save.error, []) ?? problemText(save.error)) : undefined

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-4" data-testid="profile-details">
      <p className="text-muted-foreground text-sm">{t('details.hint')}</p>
      {CRITERIA.map((criterion) => {
        const label = tQb(`audience.${criterion.key === 'age' ? 'birthYear' : criterion.key}`)
        const chosen = values[criterion.key]?.options ?? []
        if (criterion.kind === 'RANGE') {
          return (
            <div key={criterion.key} className="flex flex-col gap-2">
              <Label htmlFor={`${id}-${criterion.key}`}>{label}</Label>
              <Input id={`${id}-${criterion.key}`} inputMode="numeric" maxLength={4} value={birthYear}
                placeholder={t('details.birthYearPlaceholder')} autoComplete="bday-year"
                data-testid={`details-${criterion.key}`}
                onChange={(event) => {
                  setSaved(false)
                  setBirthYear(event.target.value.replace(/\D/g, ''))
                }} />
            </div>
          )
        }
        if (criterion.multiple) {
          return (
            <fieldset key={criterion.key} className="flex flex-col gap-2" data-testid={`details-${criterion.key}`}>
              <legend className="mb-2 text-sm font-medium">{label}</legend>
              <div className="grid grid-cols-2 gap-2">
                {criterion.options.map((option) => (
                  <label key={option} className="flex items-center gap-2 text-sm">
                    <Checkbox checked={chosen.includes(option)} data-testid={`details-${criterion.key}-${option}`}
                      onCheckedChange={(checked) => choose(criterion, checked === true
                        ? criterion.options.filter((o) => o === option || chosen.includes(o))
                        : chosen.filter((o) => o !== option))} />
                    {tQb(`audience.${criterion.key}Options.${option}`)}
                  </label>
                ))}
              </div>
            </fieldset>
          )
        }
        return (
          <div key={criterion.key} className="flex flex-col gap-2">
            <Label htmlFor={`${id}-${criterion.key}`}>{label}</Label>
            <Select value={chosen[0] ?? NOT_SET}
              onValueChange={(value) => choose(criterion, value === NOT_SET ? [] : [value])}>
              <SelectTrigger id={`${id}-${criterion.key}`} data-testid={`details-${criterion.key}`}>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value={NOT_SET}>{t('details.notSet')}</SelectItem>
                {criterion.options.map((option) => (
                  <SelectItem key={option} value={option}>{tQb(`audience.${criterion.key}Options.${option}`)}</SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        )
      })}
      <p className="text-muted-foreground text-xs">{t('details.privacy')}</p>
      {problem && <p className="text-destructive text-sm" role="alert" data-testid="details-error">{problem}</p>}
      {saved && (
        <p className="text-sm flex items-center gap-2" role="status" data-testid="details-saved">
          <CircleCheck aria-hidden className="size-4" />{tQb('saved')}
        </p>
      )}
      <Button type="submit" disabled={save.isPending || !emailVerified} data-testid="details-save">
        {t('details.save')}
      </Button>
    </form>
  )
}
