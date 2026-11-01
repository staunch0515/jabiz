import { ApiError } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Checkbox,
  Combobox,
  ConfirmDialog,
  DateTimePicker,
  Input,
  Label,
  MoneyInput,
  notify,
  RadioGroup,
  RadioGroupItem,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Steps,
  Textarea,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, ArrowRight, CircleCheck, FileText, RefreshCw, Save, Send, SlidersHorizontal, UsersRound,
  type LucideIcon } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { readVersions, searchQuizzes } from '../content/api'
import { QUIZZES_QUERY, versionsQuery } from '../content/paths'
import { SPONSOR } from '../messages'
import { ShownAt, type ShownField } from '../content/ShownAt'
import { FileField } from '../content/editing'
import { kudosName } from '../qb/kudos'
import { noticeConsentProblem } from '../qb/ConsentGate'
import { fieldProblem, problemText } from '../qb/problems'
import { navigate } from '../qb/router'
import type { Condition } from '../qb/audience'
import { AudienceConditions, AudienceCountPanel } from './AudienceConditions'
import { LanguagesFields } from './LanguagesFields'
import { ReferralFields } from './ReferralFields'
import {
  asSurvey,
  conditionsQuery,
  countAudience,
  readConditions,
  saveConditions,
  formOf,
  inputOf,
  isStale,
  PUBLISHING_LIMITS,
  readRewardSettings,
  REWARD_SETTINGS_QUERY,
  REDEEM_DESCRIPTION_LENGTH,
  DIGITAL_CONTENT_LENGTH,
  DIGITAL_DELIVERIES,
  DIGITAL_DESCRIPTION_LENGTH,
  REGIONS,
  savePublication,
  submitPublication,
  surveyReward,
  AUDIENCES,
  type Audience,
  type AudienceCount,
  type DigitalDelivery,
  type FormErrors,
  type Problem,
  type PublicationDetail,
  type PublicationForm,
  type RewardMethod,
  type SaveInput,
  type SaveOutput,
} from './api'
import { publicationPath, PUBLICATIONS_QUERY } from './paths'
import { LabelledProblems, PublicationSummary, useAudienceLabel, useDebounced, useDigitalDeliveryLabel, useRegionLabel,
  useRewardMethodLabel, useVersionOf } from './shared'
import { TiersEditor } from './TiersEditor'
import { playSound } from '../qb/sound'

export const STEP_COUNT = 4

/** A field with its label, hint and problem, the problem tied to it by `aria-describedby`. */
function Field({ id, label, hint, problem, shown, children }: {
  id: string
  label: ReactNode
  hint?: ReactNode
  problem?: string
  /** Where takers see the field (Q34b). */
  shown?: ShownField
  children: (aria: { id: string; 'aria-invalid'?: true; 'aria-describedby'?: string }) => ReactNode
}) {
  const describedBy = [hint ? `${id}-hint` : undefined, problem ? `${id}-error` : undefined].filter(Boolean).join(' ')
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {children({ id, 'aria-invalid': problem ? true : undefined, 'aria-describedby': describedBy || undefined })}
      {hint && <p id={`${id}-hint`} className="text-muted-foreground text-sm">{hint}</p>}
      {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
      {shown && <ShownAt field={shown} />}
    </div>
  )
}

/** A refusal's violations as problems (field and message), or its one text. */
function refusalProblems(error: unknown): Pick<Problem, 'field' | 'message'>[] {
  if (error === undefined || error === null || isStale(error)) return []
  if (error instanceof ApiError && error.violations.length > 0) {
    return error.violations.map((v) => ({ field: v.field ?? null, message: v.message }))
  }
  return [{ field: null, message: problemText(error) }]
}

/**
 * The four steps of a draft publication (M-30, M-32, M-33): the quiz version and name; the time, marks, reward
 * tiers and money; who it is for (Q47: public in regions or invited, and the matching conditions on the takers'
 * profiles, with their rounded count); then everything at once with every problem submitting would be refused for (the save's
 * output) and "Submit". Each "Save and continue" saves the whole draft (`QB_PUBLICATION_SAVE`, with the revision the
 * page last saw); a draft may be incomplete. A new publication is created by its first save and opened at its address.
 * A survey's (Q12) asks for its one reward instead of a pass mark and tiers.
 */
export function PublicationWizard({ detail, initialStep, onStale, stale, header }: {
  /** The draft as read; null for a new publication. */
  detail: PublicationDetail | null
  initialStep: number
  /** The publication changed elsewhere: the page holds edits until reloaded. */
  onStale: () => void
  stale: boolean
  /** The page's heading, given the revision the wizard last saw (for the actions). */
  header: (revision: number) => ReactNode
}) {
  const { t } = useTranslation(SPONSOR)
  const queryClient = useQueryClient()
  const id = useId()
  const regionLabel = useRegionLabel()
  const audienceLabel = useAudienceLabel()
  const publicationId = detail?.publication.publicationId ?? null
  const [form, setForm] = useState<PublicationForm>(() => formOf(detail))
  const [step, setStep] = useState(Math.min(Math.max(initialStep, 0), STEP_COUNT - 1))
  const [typed, setTyped] = useState<FormErrors>({})
  const [badTimes, setBadTimes] = useState({ startAt: false, endAt: false })
  const [output, setOutput] = useState<SaveOutput | null>(null)
  const [submitError, setSubmitError] = useState<unknown>(null)
  // The revision the page knows: the one read, then each of its own saves'.
  const [revision, setRevision] = useState<number | null>(detail?.publication.revision ?? null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const moved = useRef(false)
  // Q47: the matching conditions, saved with each save of the draft once changed (their own process).
  const savedConditions = useQuery({
    queryKey: conditionsQuery(publicationId),
    queryFn: () => readConditions(publicationId!),
    enabled: publicationId !== null,
  })
  const [editedConditions, setEditedConditions] = useState<Condition[] | null>(null)
  const conditions = editedConditions ?? savedConditions.data ?? []
  const [count, setCount] = useState<AudienceCount | null>(null)

  // After moving between steps, focus goes to the new step's heading (its content replaced what had focus).
  useEffect(() => {
    if (moved.current) headingRef.current?.focus()
  }, [step])
  const goTo = (next: number) => {
    moved.current = true
    setStep(next)
  }

  const [quizSearch, setQuizSearch] = useState('')
  const q = useDebounced(quizSearch, 300)
  // A new search keeps the previous choices until its answer comes (the list never empties into a spinner while
  // typing, which also left the open listbox without options); only the first search shows one.
  const quizzes = useQuery({
    queryKey: [...QUIZZES_QUERY, 'choices', q],
    queryFn: () => searchQuizzes({ q, offset: 0, limit: 20 }),
    placeholderData: keepPreviousData,
  })
  const { versions, version } = useVersionOf(form.quizId, form.versionId)
  const fullScore = version?.fullScore ?? output?.fullScore ?? detail?.publication.fullScore ?? null
  const quizTitle = version?.title ?? versions.data?.[0]?.title
    ?? quizzes.data?.items.find((quiz) => quiz.quizId === form.quizId)?.title ?? null
  // A survey (Q12) has one reward for a complete response: the page builds its pass mark and tiers.
  const survey = (version?.kind ?? quizzes.data?.items.find((quiz) => quiz.quizId === form.quizId)?.kind) === 'SURVEY'

  // Q22: the methods an administrator allowed this sponsor; the choice is shown only when there is one to make.
  const settings = useQuery({ queryKey: REWARD_SETTINGS_QUERY, queryFn: readRewardSettings, staleTime: 60_000 })
  const allowed = settings.data?.methods
  const methodLabel = useRewardMethodLabel()
  const deliveryLabel = useDigitalDeliveryLabel()
  const methodChoices: RewardMethod[] = allowed ?? ['PLATFORM']
  // A new draft starts with the first method allowed (the platform's is not always among them) until the sponsor
  // picks one; a draft already saved keeps its own, which the choice then shows as not allowed any more.
  const rewardMethod: RewardMethod = !detail && allowed && allowed.length > 0 && !allowed.includes(form.rewardMethod)
    ? allowed[0] : form.rewardMethod
  const showMethods = methodChoices.length > 1 || !methodChoices.includes(rewardMethod)

  const set = <K extends keyof PublicationForm>(field: K, value: PublicationForm[K]) => {
    // Q47: a count was of the regions and the audience it was made for.
    if (field === 'regions' || field === 'audience') setCount(null)
    setForm((f) => ({ ...f, [field]: value }))
  }

  const chooseQuiz = async (quizId: string | null) => {
    setForm((f) => ({ ...f, quizId, versionId: null }))
    if (!quizId) return
    // The newest version is the usual choice: chosen until the sponsor picks another.
    const found = await queryClient.fetchQuery({ queryKey: versionsQuery(quizId), queryFn: () => readVersions(quizId) })
    setForm((f) => (f.quizId === quizId && !f.versionId ? { ...f, versionId: found[0]?.versionId ?? null } : f))
  }

  const save = useMutation({
    mutationFn: async ({ input, criteria }: { input: SaveInput; keys: string[]; then: 'stay' | 'next' | 'count';
      criteria: Condition[] | null }) => {
      const saved = await savePublication(input)
      // The draft is saved whatever happens to its conditions next: the page knows its new revision.
      setRevision(saved.revision)
      if (criteria === null) return { saved, counted: null }
      // An invited publication takes its list: its conditions are cleared.
      const out = await saveConditions(saved.publicationId, saved.revision, input.audience === 'INVITED' ? [] : criteria)
      return { saved: { ...saved, revision: out.revision }, counted: null }
    },
    onMutate: () => setSubmitError(null),
    onSuccess: async ({ saved }, { input, keys, then, criteria }) => {
      const audienceOfSave = input.audience
      setRevision(saved.revision)
      if (criteria !== null) {
        // What was just stored, without waiting for a read (an invited one's are cleared).
        queryClient.setQueryData(conditionsQuery(saved.publicationId),
          audienceOfSave === 'INVITED' ? [] : criteria)
        setEditedConditions(null)
      }
      setOutput(saved)
      // New tiers get their keys: the next save sends them as the same tiers.
      const ids = new Map(keys.map((key, i) => [key, saved.tierIds[i]]))
      setForm((f) => ({ ...f, tiers: f.tiers.map((tier) => ({ ...tier, tierId: ids.get(tier.key) ?? tier.tierId })) }))
      await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
      const nextStep = then === 'next' ? step + 1 : step
      if (then === 'count') {
        counter.mutate(saved.publicationId)
        return
      }
      if (!publicationId) {
        notify.success(t('publishing.wizard.created'))
        navigate(`${publicationPath(saved.publicationId)}?step=${nextStep}`, { replace: true })
        return
      }
      if (then === 'next') goTo(nextStep)
      else notify.success(t('publishing.wizard.saved'))
    },
    onError: (error) => {
      if (isStale(error)) onStale()
    },
  })

  // Counts what was just saved: the regions and the conditions as stored.
  const counter = useMutation({
    mutationFn: countAudience,
    onMutate: () => setCount(null),
    onSuccess: setCount,
  })

  /** Saves the whole draft when what is typed can be sent; marks what cannot. */
  const run = (then: 'stay' | 'next' | 'count') => {
    const chosen = rewardMethod === form.rewardMethod ? form : { ...form, rewardMethod }
    const shaped = survey && fullScore !== null ? asSurvey(chosen, fullScore, surveyReward(chosen)) : chosen
    if (shaped !== form) setForm(shaped)
    const built = inputOf(shaped, publicationId, revision)
    setTyped(built.errors ?? {})
    if (built.errors || badTimes.startAt || badTimes.endAt) {
      notify.error(t('publishing.wizard.fixTyping'))
      return
    }
    // Q47: a criterion switched on needs an option or both bounds (the server would refuse it as well).
    if (shaped.audience === 'PUBLIC' && editedConditions?.some((condition) => condition.options
      ? condition.options.length === 0 : condition.min == null || condition.max == null)) {
      notify.error(t('publishing.audience.incomplete'))
      return
    }
    save.mutate({ input: built.input, keys: shaped.tiers.map((tier) => tier.key), then,
      criteria: editedConditions ?? (shaped.audience === 'INVITED' && conditions.length > 0 ? [] : null) })
  }

  const submit = () => {
    setSubmitError(null)
    return submitPublication(publicationId!, revision ?? 0).then(
      async (out) => {
        notify.success(out.status === 'PENDING_REVIEW' ? t('publishing.wizard.submitted') : t('publishing.wizard.published'))
        playSound('success')
        await queryClient.invalidateQueries({ queryKey: PUBLICATIONS_QUERY })
      },
      (error: unknown) => {
        if (isStale(error)) onStale()
        else setSubmitError(error)
        noticeConsentProblem(queryClient, error)
      },
    )
  }

  /** A field's problem: what is typed wrong, else the server's refusal of the last save. */
  const problemOf = (field: string): string | undefined => {
    const wrong = typed[field]
    if (wrong) return t(`publishing.errors.${wrong}`)
    return isStale(save.error) ? undefined : fieldProblem(save.error, field)
  }

  const locked = stale || save.isPending
  const saveProblems = refusalProblems(save.error)
  const problems = output?.problems ?? []
  const stepTitles = [t('publishing.steps.content'), t('publishing.steps.policy'), t('publishing.steps.audience'),
    t('publishing.steps.confirm')]
  /** A step's name with a picture of it (Q32); the picture is decorative. */
  const stepTitle = (Icon: LucideIcon, title: string) => (
    <span className="inline-flex items-center gap-1.5"><Icon aria-hidden className="size-4" />{title}</span>
  )

  const steps: ReactNode[] = [
    // 1. The quiz version and the name.
    <div key="content" className="flex flex-col gap-4">
      <Field id={`${id}-name`} label={t('publishing.field.name')} problem={problemOf('name')} shown="publicationName"
        hint={t('publishing.field.nameHint', { max: PUBLISHING_LIMITS.name })}>
        {(aria) => (
          <Input {...aria} value={form.name} maxLength={PUBLISHING_LIMITS.name} autoComplete="off" required
            disabled={locked} data-testid="publication-name" onChange={(event) => set('name', event.target.value)} />
        )}
      </Field>
      {/* Q64: the publication's own cover, the same upload as the quiz's; empty, the quiz's is shown. */}
      <div className="flex flex-col gap-1.5">
        <FileField kind="image" label={t('publishing.field.cover')}
          uploadLabel={form.cover ? t('publishing.field.replaceCover') : t('publishing.field.uploadCover')}
          value={form.cover} onChange={(cover) => set('cover', cover)} error={problemOf('cover')} disabled={locked}
          testId="publication-cover" />
        <p className="text-muted-foreground text-sm" data-testid="publication-cover-hint">{t('publishing.field.coverHint')}</p>
        <ShownAt field="cover" />
      </div>
      <Field id={`${id}-quiz`} label={t('publishing.field.quiz')} hint={t('publishing.field.quizHint')}>
        {(aria) => (
          <Combobox {...aria} value={form.quizId} selectedLabel={quizTitle ?? undefined}
            onChange={(quizId) => void chooseQuiz(quizId)} onSearch={setQuizSearch} loading={quizzes.isPending}
            options={(quizzes.data?.items ?? []).map((quiz) => ({
              value: quiz.quizId,
              label: quiz.title,
              description: quiz.versionLabel ?? t('publishing.field.quizNoVersion'),
              disabled: quiz.latestVersionNo === 0,
            }))}
            placeholder={t('publishing.field.quizPlaceholder')} searchPlaceholder={t('publishing.field.quizSearch')}
            emptyText={t('publishing.field.quizNone')} disabled={locked} className="w-full max-w-md"
            data-testid="publication-quiz" />
        )}
      </Field>
      {form.quizId && (
        <Field id={`${id}-version`} label={t('publishing.field.versionId')} problem={problemOf('versionId')}
          hint={versions.data && versions.data.length === 0 ? t('publishing.field.noVersions') : undefined}>
          {(aria) => (
            <Select value={form.versionId ?? ''} disabled={locked || !versions.data?.length}
              onValueChange={(value) => {
                // Radix reports "" when the options change under a chosen value; only a real choice counts.
                if (value) set('versionId', value)
              }}>
              <SelectTrigger {...aria} className="w-full max-w-md" data-testid="publication-version">
                <SelectValue placeholder={t('publishing.field.versionPlaceholder')} />
              </SelectTrigger>
              <SelectContent>
                {(versions.data ?? []).map((v) => (
                  <SelectItem key={v.versionId} value={v.versionId}>
                    {t('publishing.field.versionValue', { label: v.label, questions: v.questionCount,
                      fullScore: v.fullScore })}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          )}
        </Field>
      )}
    </div>,

    // 2. Who sees it and when, the marks, the rewards and the money.
    <div key="policy" className="flex flex-col gap-6">
      {settings.data?.maxRewardPerAttempt != null && (
        <p className="text-muted-foreground text-sm" data-testid="publication-reward-cap">
          {t('publishing.field.rewardCap', { max: settings.data.maxRewardPerAttempt.toLocaleString() })}
        </p>
      )}
      {showMethods && (
        <fieldset className="flex flex-col gap-2" data-testid="publication-method"
          aria-describedby={[`${id}-method-hint`, problemOf('rewardMethod') ? `${id}-method-error` : undefined]
            .filter(Boolean).join(' ')}>
          <legend className="text-sm font-medium">{t('publishing.field.rewardMethod')}</legend>
          <p id={`${id}-method-hint`} className="text-muted-foreground text-sm">{t('publishing.field.rewardMethodHint')}</p>
          <RadioGroup value={rewardMethod} disabled={locked}
            onValueChange={(value) => set('rewardMethod', value as RewardMethod)}>
            {methodChoices.map((method) => (
              <div key={method} className="flex items-start gap-2">
                <RadioGroupItem id={`${id}-method-${method}`} value={method} aria-describedby={`${id}-method-${method}-hint`}
                  data-testid={`publication-method-${method}`} />
                <div className="flex flex-col">
                  <Label htmlFor={`${id}-method-${method}`} className="font-normal">{methodLabel(method)}</Label>
                  <span id={`${id}-method-${method}-hint`} className="text-muted-foreground text-xs">
                    {t(`publishing.methods.${method}`)}
                  </span>
                </div>
              </div>
            ))}
          </RadioGroup>
          {problemOf('rewardMethod') && (
            <p id={`${id}-method-error`} className="text-destructive text-sm">{problemOf('rewardMethod')}</p>
          )}
        </fieldset>
      )}
      {rewardMethod === 'OFFLINE_REDEEM' && (
        <Field id={`${id}-redeem`} label={t('publishing.field.redeemDescription')} problem={problemOf('redeemDescription')}
          shown="prizeDescription"
          hint={t('publishing.field.redeemDescriptionHint', { max: REDEEM_DESCRIPTION_LENGTH })}>
          {(aria) => (
            <Textarea {...aria} value={form.redeemDescription} rows={3} maxLength={REDEEM_DESCRIPTION_LENGTH}
              disabled={locked} data-testid="publication-redeem"
              onChange={(event) => set('redeemDescription', event.target.value)} />
          )}
        </Field>
      )}
      {rewardMethod === 'DIGITAL_PRIZE' && (
        // Q33: how the prize reaches the winner, and what each way needs. The fields of another way keep what was
        // typed but are not sent (`inputOf`).
        <div className="flex flex-col gap-4">
          <fieldset className="flex flex-col gap-2" data-testid="publication-digital-delivery"
            aria-describedby={[`${id}-delivery-hint`, problemOf('digitalDelivery') ? `${id}-delivery-error` : undefined]
              .filter(Boolean).join(' ')}>
            <legend className="text-sm font-medium">{t('publishing.field.digitalDelivery')}</legend>
            <p id={`${id}-delivery-hint`} className="text-muted-foreground text-sm">{t('publishing.field.digitalDeliveryHint')}</p>
            <RadioGroup value={form.digitalDelivery ?? ''} disabled={locked}
              onValueChange={(value) => set('digitalDelivery', value as DigitalDelivery)}>
              {DIGITAL_DELIVERIES.map((delivery) => (
                <div key={delivery} className="flex items-start gap-2">
                  <RadioGroupItem id={`${id}-delivery-${delivery}`} value={delivery}
                    aria-describedby={`${id}-delivery-${delivery}-hint`} data-testid={`publication-digital-delivery-${delivery}`} />
                  <div className="flex flex-col">
                    <Label htmlFor={`${id}-delivery-${delivery}`} className="font-normal">{deliveryLabel(delivery)}</Label>
                    <span id={`${id}-delivery-${delivery}-hint`} className="text-muted-foreground text-xs">
                      {t(`publishing.deliveries.${delivery}`)}
                    </span>
                  </div>
                </div>
              ))}
            </RadioGroup>
            {problemOf('digitalDelivery') && (
              <p id={`${id}-delivery-error`} className="text-destructive text-sm">{problemOf('digitalDelivery')}</p>
            )}
          </fieldset>
          <Field id={`${id}-digital-description`} label={t('publishing.field.digitalDescription')}
            problem={problemOf('digitalDescription')} shown="prizeDescription"
            hint={t('publishing.field.digitalDescriptionHint', { max: DIGITAL_DESCRIPTION_LENGTH })}>
            {(aria) => (
              <Textarea {...aria} value={form.digitalDescription} rows={3} maxLength={DIGITAL_DESCRIPTION_LENGTH}
                disabled={locked} data-testid="publication-digital-description"
                onChange={(event) => set('digitalDescription', event.target.value)} />
            )}
          </Field>
          {form.digitalDelivery === 'SHARED' && (
            <Field id={`${id}-digital-content`} label={t('publishing.field.digitalContent')}
              problem={problemOf('digitalContent')} hint={t('publishing.field.digitalContentHint')}>
              {(aria) => (
                <Textarea {...aria} value={form.digitalContent} rows={2} maxLength={DIGITAL_CONTENT_LENGTH}
                  autoComplete="off" spellCheck={false} disabled={locked} data-testid="publication-digital-content"
                  onChange={(event) => set('digitalContent', event.target.value)} />
              )}
            </Field>
          )}
          {form.digitalDelivery === 'SPONSOR_SENT' && (
            <Field id={`${id}-digital-send-days`} label={t('publishing.field.digitalSendDays')}
              problem={problemOf('digitalSendDays')} hint={t('publishing.field.digitalSendDaysHint')}>
              {(aria) => (
                <Input {...aria} inputMode="numeric" value={form.digitalSendDays} autoComplete="off" disabled={locked}
                  className="max-w-32" data-testid="publication-digital-send-days"
                  onChange={(event) => set('digitalSendDays', event.target.value)} />
              )}
            </Field>
          )}
          {form.digitalDelivery === 'POOL' && (
            <p className="text-muted-foreground text-sm" data-testid="publication-digital-pool-note">
              {t('publishing.field.digitalPoolNote')}
            </p>
          )}
        </div>
      )}
      {/* Q70: "referrals welcome": the sponsor's offer to referrers, frozen with the rest at submission. */}
      <ReferralFields id={id} form={form} set={set} locked={locked} problemOf={problemOf} methods={methodChoices} />
      {/* Q73b: the languages offered and the publication's texts in them. */}
      <LanguagesFields id={id} form={form} set={set} version={version} method={rewardMethod} locked={locked}
        problemOf={problemOf} publicationId={publicationId} />
      <div className="grid gap-4 md:grid-cols-2">
        <Field id={`${id}-start`} label={t('publishing.field.startAt')} problem={problemOf('startAt')}
          hint={t('publishing.field.periodHint')}>
          {(aria) => (
            <DateTimePicker {...aria} value={form.startAt} clearable disabled={locked} data-testid="publication-start"
              onInvalidChange={(invalid) => setBadTimes((b) => ({ ...b, startAt: invalid }))}
              onChange={(value) => set('startAt', value)} />
          )}
        </Field>
        <Field id={`${id}-end`} label={t('publishing.field.endAt')} problem={problemOf('endAt')}>
          {(aria) => (
            <DateTimePicker {...aria} value={form.endAt} clearable disabled={locked} data-testid="publication-end"
              onInvalidChange={(invalid) => setBadTimes((b) => ({ ...b, endAt: invalid }))}
              onChange={(value) => set('endAt', value)} />
          )}
        </Field>
      </div>
      <div className="grid gap-4 md:grid-cols-2">
        <Field id={`${id}-time-limit`} label={t('publishing.field.timeLimitSec')} problem={problemOf('timeLimitSec')}
          hint={t('publishing.field.timeLimitHint', { min: PUBLISHING_LIMITS.minTimeLimitSec,
            max: PUBLISHING_LIMITS.maxTimeLimitSec.toLocaleString() })}>
          {(aria) => (
            <Input {...aria} inputMode="numeric" value={form.timeLimitSec} autoComplete="off" disabled={locked}
              data-testid="publication-time-limit" onChange={(event) => set('timeLimitSec', event.target.value)} />
          )}
        </Field>
        {survey ? (
          <Field id={`${id}-survey-reward`} label={t('publishing.field.surveyReward')}
            problem={problemOf(`tiers[1].reward`) ?? problemOf('tiers')} hint={t('publishing.field.surveyRewardHint')}>
            {(aria) => (
              <MoneyInput {...aria} value={surveyReward(form)} scale={0} allowNegative={false} unit={kudosName()}
                autoComplete="off" disabled={locked || fullScore === null} data-testid="publication-survey-reward"
                onChange={(value) => fullScore !== null && setForm((f) => asSurvey(f, fullScore, value))} />
            )}
          </Field>
        ) : (
          <Field id={`${id}-pass`} label={t('publishing.field.passScore')} problem={problemOf('passScore')}
            hint={fullScore === null ? t('publishing.field.passScoreHintNone')
              : t('publishing.field.passScoreHint', { fullScore })}>
            {(aria) => (
              <Input {...aria} inputMode="numeric" value={form.passScore} autoComplete="off" disabled={locked}
                data-testid="publication-pass-score" onChange={(event) => set('passScore', event.target.value)} />
            )}
          </Field>
        )}
      </div>
      {!survey && (
        <TiersEditor tiers={form.tiers} onChange={(tiers) => set('tiers', tiers)} fullScore={fullScore}
          problemOf={problemOf} disabled={locked} />
      )}
      <div className="grid gap-4 md:grid-cols-2">
        <Field id={`${id}-budget`} label={t('publishing.field.budgetCap')} problem={problemOf('budgetCap')}
          hint={t('publishing.field.budgetHint')}>
          {(aria) => (
            <MoneyInput {...aria} value={form.budgetCap} scale={0} allowNegative={false} unit={kudosName()}
              autoComplete="off" disabled={locked} data-testid="publication-budget"
              onChange={(value) => set('budgetCap', value)} />
          )}
        </Field>
        <Field id={`${id}-winners`} label={t('publishing.field.maxWinners')} problem={problemOf('maxWinners')}
          hint={t('publishing.field.maxWinnersHint')}>
          {(aria) => (
            <Input {...aria} inputMode="numeric" value={form.maxWinners} autoComplete="off" disabled={locked}
              data-testid="publication-max-winners" onChange={(event) => set('maxWinners', event.target.value)} />
          )}
        </Field>
      </div>
    </div>,

    // 3. Who it is for (Q47): public in regions, or invited; and the takers' profiles it is aimed at.
    <div key="audience" className="flex flex-col gap-6">
      <fieldset className="flex flex-col gap-2" data-testid="publication-audience"
        aria-describedby={[`${id}-audience-hint`, problemOf('audience') ? `${id}-audience-error` : undefined]
          .filter(Boolean).join(' ')}>
        <legend className="text-sm font-medium">{t('publishing.field.audience')}</legend>
        <p id={`${id}-audience-hint`} className="text-muted-foreground text-sm">{t('publishing.field.audienceHint')}</p>
        <RadioGroup value={form.audience} disabled={locked} onValueChange={(value) => set('audience', value as Audience)}>
          {AUDIENCES.map((audience) => (
            <div key={audience} className="flex items-start gap-2">
              <RadioGroupItem id={`${id}-audience-${audience}`} value={audience}
                aria-describedby={`${id}-audience-${audience}-hint`} data-testid={`publication-audience-${audience}`} />
              <div className="flex flex-col">
                <Label htmlFor={`${id}-audience-${audience}`} className="font-normal">{audienceLabel(audience)}</Label>
                <span id={`${id}-audience-${audience}-hint`} className="text-muted-foreground text-xs">
                  {t(`publishing.audiences.${audience}`)}
                </span>
              </div>
            </div>
          ))}
        </RadioGroup>
        {problemOf('audience') && (
          <p id={`${id}-audience-error`} className="text-destructive text-sm">{problemOf('audience')}</p>
        )}
      </fieldset>
      {form.audience === 'PUBLIC' && (
      <fieldset className="flex flex-col gap-2" aria-describedby={[`${id}-regions-hint`,
        problemOf('regions') ? `${id}-regions-error` : undefined].filter(Boolean).join(' ')} data-testid="publication-regions">
        <legend className="text-sm font-medium">{t('publishing.field.regions')}</legend>
        <p id={`${id}-regions-hint`} className="text-muted-foreground text-sm">{t('publishing.field.regionsHint')}</p>
        <div className="grid gap-x-6 gap-y-3 sm:grid-cols-2 lg:grid-cols-4">
          {REGIONS.map((region) => (
            <div key={region} className="flex items-center gap-2">
              <Checkbox id={`${id}-region-${region}`} checked={form.regions.includes(region)} disabled={locked}
                data-testid="publication-region" data-region={region}
                onCheckedChange={(checked) => set('regions', checked === true
                  ? [...form.regions.filter((r) => r !== region), region]
                  : form.regions.filter((r) => r !== region))} />
              <Label htmlFor={`${id}-region-${region}`} className="font-normal">{regionLabel(region)}</Label>
            </div>
          ))}
        </div>
        {problemOf('regions') && (
          <p id={`${id}-regions-error`} className="text-destructive text-sm">{problemOf('regions')}</p>
        )}
      </fieldset>
      )}
      {form.audience === 'PUBLIC' && (
        <div className="flex flex-col gap-3" data-testid="publication-conditions">
          <div className="flex flex-col gap-1">
            <h3 className="text-sm font-medium">{t('publishing.audience.title')}</h3>
            <p className="text-muted-foreground text-sm">{t('publishing.audience.hint')}</p>
          </div>
          {publicationId && savedConditions.isPending
            ? <p className="text-muted-foreground text-sm">{t('publishing.audience.loading')}</p>
            : <AudienceConditions value={conditions} disabled={locked} onChange={(next) => {
              setEditedConditions(next)
              setCount(null)
            }} />}
          <AudienceCountPanel count={count} pending={counter.isPending || (save.isPending
            && save.variables?.then === 'count')} disabled={locked} onCount={() => run('count')} />
          {counter.isError && <p className="text-destructive text-sm" role="alert">{problemText(counter.error)}</p>}
        </div>
      )}
    </div>,

    // 4. Everything at once, every problem, and submitting.
    <div key="confirm" className="flex flex-col gap-4">
      {problems.length > 0
        ? (
          <div className="flex flex-col gap-2">
            <p className="text-sm font-medium">{t('publishing.wizard.cannotSubmit')}</p>
            <LabelledProblems problems={problems} testId="publication-problems" />
          </div>
        )
        : output && (
          <p className="text-success flex items-center gap-2 text-sm" data-testid="publication-ready">
            <CircleCheck aria-hidden className="size-4" />
            {t('publishing.wizard.ready')}
          </p>
        )}
      {refusalProblems(submitError).length > 0 && (
        <div className="flex flex-col gap-2" role="alert">
          <p className="text-sm font-medium">{t('publishing.wizard.submitRefused')}</p>
          <LabelledProblems problems={refusalProblems(submitError)} testId="publication-submit-problems" />
        </div>
      )}
      <PublicationSummary values={{
        name: form.name,
        cover: form.cover,
        quizTitle,
        versionLabel: version?.label ?? detail?.publication.versionLabel ?? null,
        regions: form.audience === 'INVITED' ? [] : REGIONS.filter((region) => form.regions.includes(region)),
        audience: form.audience,
        conditions: form.audience === 'INVITED' ? [] : conditions,
        startAt: form.startAt,
        endAt: form.endAt,
        // Left empty, the time limit is the version's: the server wrote it with the last save.
        timeLimitSec: form.timeLimitSec.trim() === '' ? detail?.publication.timeLimitSec ?? null
          : Number(form.timeLimitSec),
        passScore: form.passScore.trim() === '' ? null : Number(form.passScore),
        fullScore,
        budgetCap: form.budgetCap.trim() === '' ? null : form.budgetCap,
        maxWinners: form.maxWinners.trim() === '' ? null : Number(form.maxWinners),
        maxReward: output?.maxReward ?? null,
        maxSpend: output?.maxSpend ?? null,
        tiers: form.tiers.map((tier) => ({ fromScore: Number(tier.fromScore), toScore: Number(tier.toScore),
          reward: tier.reward })),
        rewardMethod,
        redeemDescription: form.redeemDescription.trim() || null,
        digitalDelivery: form.digitalDelivery,
        digitalDescription: form.digitalDescription.trim() || null,
        digitalSendDays: form.digitalSendDays.trim() === '' ? null : Number(form.digitalSendDays),
        referral: form.referralEnabled ? { quota: Number(form.referralQuota), method: form.referralMethod,
          amount: Number(form.referralAmount), description: form.referralDescription.trim() || null,
          referrerRequires: form.referrerRequires, refereeRules: form.refereeRules,
          days: Number(form.referralDays || 7) } : null,
      }} />
    </div>,
  ]

  return (
    <section className="flex flex-col gap-4" data-testid="publication-wizard" data-step={step}>
      {header(revision ?? 0)}
      <Steps current={step} aria-label={t('publishing.steps.label')} data-testid="publication-steps" items={[
        { key: 'content', title: stepTitle(FileText, stepTitles[0]), description: t('publishing.steps.contentHint') },
        { key: 'policy', title: stepTitle(SlidersHorizontal, stepTitles[1]), description: t('publishing.steps.policyHint') },
        { key: 'audience', title: stepTitle(UsersRound, stepTitles[2]), description: t('publishing.steps.audienceHint') },
        { key: 'confirm', title: stepTitle(CircleCheck, stepTitles[3]), description: t('publishing.steps.confirmHint') },
      ]} />
      <form noValidate className="flex flex-col gap-6" aria-labelledby={`${id}-step`} onSubmit={(event) => {
        event.preventDefault()
        if (step < STEP_COUNT - 1) run('next')
      }}>
        <h2 id={`${id}-step`} ref={headingRef} tabIndex={-1} className="text-lg font-semibold outline-none"
          data-testid="publication-step-title">
          {stepTitles[step]}
        </h2>
        {steps[step]}
        {saveProblems.length > 0 && (
          <div className="flex flex-col gap-2" role="alert">
            <p className="text-sm font-medium">{t('publishing.wizard.refused')}</p>
            <LabelledProblems problems={saveProblems} testId="publication-save-problems" />
          </div>
        )}
        <div className="flex flex-wrap gap-2 border-t pt-4">
          {step > 0 && (
            <Button type="button" variant="outline" disabled={save.isPending} data-testid="publication-back"
              onClick={() => goTo(step - 1)}>
              <ArrowLeft aria-hidden />
              {t('publishing.wizard.back')}
            </Button>
          )}
          {step < STEP_COUNT - 1 && (
            <>
              <Button type="button" variant="outline" disabled={locked} aria-busy={save.isPending || undefined}
                data-testid="publication-save" onClick={() => run('stay')}>
                <Save aria-hidden />
                {t('publishing.wizard.saveDraft')}
              </Button>
              <Button type="submit" disabled={locked} aria-busy={save.isPending || undefined}
                data-testid="publication-next">
                {t('publishing.wizard.next')}
                <ArrowRight aria-hidden />
              </Button>
            </>
          )}
          {step === STEP_COUNT - 1 && publicationId && (
            <ConfirmDialog title={t('publishing.wizard.submitTitle')} description={t('publishing.wizard.submitText')}
              confirmLabel={t('publishing.wizard.submit')} cancelLabel={t('common.cancel')} onConfirm={submit}
              trigger={
                <Button type="button" disabled={stale || !output || problems.length > 0}
                  data-testid="publication-submit">
                  <Send aria-hidden />
                  {t('publishing.wizard.submit')}
                </Button>
              } />
          )}
        </div>
      </form>
    </section>
  )
}

/** The banner of a page out of date: edits wait until it is reloaded. */
export function StaleBanner({ onReload }: { onReload: () => void }) {
  const { t } = useTranslation(SPONSOR)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => ref.current?.focus(), [])
  return (
    <Alert variant="warning" ref={ref} tabIndex={-1} data-testid="publication-stale">
      <AlertTitle>{t('publishing.stale.title')}</AlertTitle>
      <AlertDescription className="flex flex-col items-start gap-3">
        <span>{t('publishing.stale.text')}</span>
        <Button size="sm" data-testid="publication-reload" onClick={onReload}>
          <RefreshCw aria-hidden />
          {t('publishing.stale.reload')}
        </Button>
      </AlertDescription>
    </Alert>
  )
}
