import { ApiError } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  ConfirmDialog,
  Input,
  Label,
  RadioGroup,
  RadioGroupItem,
  Textarea,
  useFieldArray,
  useForm,
  useWatch,
  type Control,
  type UseFormReturn,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import {
  CAMPAIGN_LIMITS as LIMITS,
  listCampaigns,
  moveCampaign,
  publishCampaign,
  saveCampaign,
  type Campaign,
  type CampaignDraft,
  type Connection,
} from './api'
import { SHOPIFY } from './messages'

/**
 * The store's quiz campaigns (docs/quizbuks/shopify/S2b-ui.md section 1): the pool's figures, the campaigns (the
 * running one first) with what each may do now, and the editor of a draft with its storefront preview. Every rule is
 * the server's (CampaignProcesses); the form only says the limits before the server does.
 */
export function CampaignsPage({ connection }: { connection: Connection }) {
  const { t } = useTranslation(SHOPIFY)
  const queryClient = useQueryClient()
  const listed = useQuery({ queryKey: ['qbs.campaigns'], queryFn: listCampaigns, refetchOnWindowFocus: false })
  const [editing, setEditing] = useState<CampaignDraft | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function act(work: () => Promise<unknown>, done: string) {
    setBusy(true)
    setProblem(null)
    setNotice(null)
    try {
      await work()
      await queryClient.invalidateQueries({ queryKey: ['qbs.campaigns'] })
      setNotice(done)
    } catch (error) {
      setProblem(reasonOf(error))
    } finally {
      setBusy(false)
    }
  }

  if (editing) {
    return (
      <CampaignEditor
        draft={editing}
        onDone={async (saved) => {
          setEditing(null)
          await queryClient.invalidateQueries({ queryKey: ['qbs.campaigns'] })
          setNotice(t('campaigns.saved', { name: saved.name }))
        }}
        onCancel={() => setEditing(null)}
      />
    )
  }

  const campaigns = [...(listed.data?.campaigns ?? [])].sort((a, b) => rank(a.status) - rank(b.status))
  const pool = listed.data?.pool
  return (
    <div className="flex flex-col gap-4">
      {connection !== 'CONNECTED' && (
        <Alert>
          <AlertDescription>{t('campaigns.connectFirst')}</AlertDescription>
        </Alert>
      )}
      {pool && (
        <Card>
          <CardHeader>
            <CardTitle>{t('campaigns.pool.title')}</CardTitle>
            <CardDescription>{t('campaigns.pool.explain')}</CardDescription>
          </CardHeader>
          <CardContent>
            <dl className="grid grid-cols-3 gap-4" data-testid="pool">
              <Figure label={t('campaigns.pool.available')} value={pool.available} />
              <Figure label={t('campaigns.pool.today')} value={pool.issuedToday} />
              <Figure label={t('campaigns.pool.week')} value={pool.issuedWeek} />
            </dl>
          </CardContent>
        </Card>
      )}
      <div>
        <Button onClick={() => setEditing(emptyDraft())}>{t('campaigns.new')}</Button>
      </div>
      {listed.isPending && <p role="status">{t('campaigns.loading')}</p>}
      {listed.error && (
        <Alert variant="destructive">
          <AlertDescription>{reasonOf(listed.error)}</AlertDescription>
        </Alert>
      )}
      {listed.data && campaigns.length === 0 && <p>{t('campaigns.none')}</p>}
      {campaigns.map((campaign) => (
        <Card key={campaign.campaignId} data-testid="campaign">
          <CardHeader>
            <CardTitle className="flex flex-wrap items-center gap-2">
              {campaign.name}
              <Badge variant={campaign.status === 'LIVE' ? 'default' : 'secondary'}>
                {t(`campaigns.status.${campaign.status}`)}
              </Badge>
            </CardTitle>
            <CardDescription>
              {t('campaigns.summary', { percent: campaign.percent, questions: campaign.questions.length,
                pass: campaign.passScore })}
            </CardDescription>
          </CardHeader>
          <CardContent className="flex flex-wrap gap-2">
            {campaign.status === 'DRAFT' && (
              <>
                <Button variant="outline" disabled={busy} onClick={() => setEditing(draftOf(campaign, true))}>
                  {t('campaigns.edit')}
                </Button>
                <ConfirmDialog
                  title={t('campaigns.publishConfirm.title')}
                  description={t('campaigns.publishConfirm.text', { percent: campaign.percent })}
                  confirmLabel={t('campaigns.publish')}
                  onConfirm={() => act(() => publishCampaign(campaign.campaignId), t('campaigns.published'))}
                  trigger={<Button disabled={busy || connection !== 'CONNECTED'}>{t('campaigns.publish')}</Button>}
                />
                <ConfirmDialog
                  title={t('campaigns.discardConfirm')}
                  confirmLabel={t('campaigns.discard')}
                  destructive
                  onConfirm={() => act(() => moveCampaign(campaign.campaignId, 'ENDED'), t('campaigns.discarded'))}
                  trigger={<Button variant="outline" disabled={busy}>{t('campaigns.discard')}</Button>}
                />
              </>
            )}
            {campaign.status === 'LIVE' && (
              <Button variant="outline" disabled={busy}
                onClick={() => void act(() => moveCampaign(campaign.campaignId, 'PAUSED'), t('campaigns.paused'))}>
                {t('campaigns.pause')}
              </Button>
            )}
            {campaign.status === 'PAUSED' && (
              <Button disabled={busy}
                onClick={() => void act(() => moveCampaign(campaign.campaignId, 'LIVE'), t('campaigns.resumed'))}>
                {t('campaigns.resume')}
              </Button>
            )}
            {(campaign.status === 'LIVE' || campaign.status === 'PAUSED') && (
              <ConfirmDialog
                title={t('campaigns.endConfirm.title')}
                description={t('campaigns.endConfirm.text')}
                confirmLabel={t('campaigns.end')}
                destructive
                onConfirm={() => act(() => moveCampaign(campaign.campaignId, 'ENDED'), t('campaigns.ended'))}
                trigger={<Button variant="outline" disabled={busy}>{t('campaigns.end')}</Button>}
              />
            )}
            {campaign.status !== 'DRAFT' && (
              <Button variant="outline" disabled={busy} onClick={() => setEditing(draftOf(campaign, false))}>
                {t('campaigns.copy')}
              </Button>
            )}
          </CardContent>
        </Card>
      ))}
      {notice && <p role="status">{notice}</p>}
      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
    </div>
  )
}

function Figure({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className="text-2xl font-semibold">{value}</dd>
    </div>
  )
}

/** The running campaign first, then drafts, then ended ones (each kind newest first, as the server lists them). */
function rank(status: Campaign['status']): number {
  return { LIVE: 0, PAUSED: 0, DRAFT: 1, ENDED: 2 }[status]
}

function reasonOf(error: unknown): string {
  if (error instanceof ApiError && error.violations.length > 0) return error.violations.map((v) => v.message).join(' ')
  return error instanceof Error ? error.message : String(error)
}

function emptyDraft(): CampaignDraft {
  return {
    name: '', video: null, timeLimitSec: 120, passScore: 1, percent: 10,
    questions: [{ stem: '', options: [{ text: '', correct: true }, { text: '', correct: false }] }],
  }
}

/** A campaign to edit (a draft, with its revision) or to start a new draft from (a copy). */
function draftOf(campaign: Campaign, edit: boolean): CampaignDraft {
  return {
    ...(edit ? { campaignId: campaign.campaignId, baseRevision: campaign.revision } : {}),
    name: campaign.name, video: campaign.video, timeLimitSec: campaign.timeLimitSec, passScore: campaign.passScore,
    percent: campaign.percent,
    questions: campaign.questions.map((q) => ({ stem: q.stem, options: q.options.map((o) => ({ ...o })) })),
  }
}

// ---- the editor ------------------------------------------------------------------------------------------------

interface FormQuestion {
  stem: string
  options: { text: string }[]
  /** The correct option's index, as the radio group's value. */
  correct: string
}

interface FormValues {
  name: string
  video: string
  timeLimitSec: number
  passScore: number
  percent: number
  questions: FormQuestion[]
}

function toForm(draft: CampaignDraft): FormValues {
  return {
    name: draft.name, video: draft.video ?? '', timeLimitSec: draft.timeLimitSec, passScore: draft.passScore,
    percent: draft.percent,
    questions: draft.questions.map((q) => ({
      stem: q.stem,
      options: q.options.map((o) => ({ text: o.text })),
      correct: String(Math.max(0, q.options.findIndex((o) => o.correct))),
    })),
  }
}

function fromForm(values: FormValues, draft: CampaignDraft): CampaignDraft {
  return {
    ...(draft.campaignId ? { campaignId: draft.campaignId, baseRevision: draft.baseRevision } : {}),
    name: values.name.trim(),
    video: values.video.trim() === '' ? null : values.video.trim(),
    timeLimitSec: Number(values.timeLimitSec),
    passScore: Number(values.passScore),
    percent: Number(values.percent),
    questions: values.questions.map((q) => ({
      stem: q.stem.trim(),
      options: q.options.map((o, i) => ({ text: o.text.trim(), correct: String(i) === q.correct })),
    })),
  }
}

function CampaignEditor({ draft, onDone, onCancel }: {
  draft: CampaignDraft
  onDone: (saved: Campaign) => Promise<void>
  onCancel: () => void
}) {
  const { t } = useTranslation(SHOPIFY)
  const form = useForm<FormValues>({ defaultValues: toForm(draft) })
  const questions = useFieldArray({ control: form.control, name: 'questions' })
  const [problem, setProblem] = useState<string | null>(null)
  const errors = form.formState.errors

  async function submit(values: FormValues) {
    setProblem(null)
    try {
      await onDone(await saveCampaign(fromForm(values, draft)))
    } catch (error) {
      setProblem(error instanceof ApiError && error.status === 409 ? t('campaigns.form.changed') : reasonOf(error))
    }
  }

  const required = { required: t('campaigns.form.required') }
  return (
    <form className="flex flex-col gap-4" noValidate onSubmit={form.handleSubmit(submit)}>
      <h2 className="text-xl font-semibold">{draft.campaignId ? t('campaigns.form.editTitle')
        : t('campaigns.form.newTitle')}</h2>
      <Card>
        <CardContent className="grid gap-4 pt-6 sm:grid-cols-2">
          <Field id="name" label={t('campaigns.form.name')} error={errors.name?.message} className="sm:col-span-2">
            <Input id="name" maxLength={LIMITS.nameLength} {...form.register('name', required)} />
          </Field>
          <Field id="video" label={t('campaigns.form.video')} hint={t('campaigns.form.videoHint')}
            error={errors.video?.message} className="sm:col-span-2">
            <Input id="video" type="url" inputMode="url" maxLength={LIMITS.videoLength} {...form.register('video')} />
          </Field>
          <Field id="timeLimitSec" label={t('campaigns.form.timeLimit')} error={errors.timeLimitSec?.message}>
            <Input id="timeLimitSec" type="number" min={LIMITS.minTimeLimitSec} max={LIMITS.maxTimeLimitSec}
              {...form.register('timeLimitSec', { ...required, valueAsNumber: true,
                min: { value: LIMITS.minTimeLimitSec, message: t('campaigns.form.range', LIMITS_TIME) },
                max: { value: LIMITS.maxTimeLimitSec, message: t('campaigns.form.range', LIMITS_TIME) } })} />
          </Field>
          <Field id="percent" label={t('campaigns.form.percent')} error={errors.percent?.message}>
            <Input id="percent" type="number" min={LIMITS.minPercent} max={LIMITS.maxPercent}
              {...form.register('percent', { ...required, valueAsNumber: true,
                min: { value: LIMITS.minPercent, message: t('campaigns.form.range', LIMITS_PERCENT) },
                max: { value: LIMITS.maxPercent, message: t('campaigns.form.range', LIMITS_PERCENT) } })} />
          </Field>
          <Field id="passScore" label={t('campaigns.form.passScore')} error={errors.passScore?.message}>
            <Input id="passScore" type="number" min={1} max={questions.fields.length}
              {...form.register('passScore', { ...required, valueAsNumber: true,
                validate: (value) => (value >= 1 && value <= form.getValues('questions').length)
                  || t('campaigns.form.passRange', { max: form.getValues('questions').length }) })} />
          </Field>
        </CardContent>
      </Card>

      {questions.fields.map((field, index) => (
        <QuestionCard key={field.id} form={form} index={index} count={questions.fields.length}
          onRemove={() => questions.remove(index)}
          onMove={(to) => questions.move(index, to)} />
      ))}
      <div>
        <Button type="button" variant="outline" disabled={questions.fields.length >= LIMITS.maxQuestions}
          onClick={() => questions.append({ stem: '', options: [{ text: '' }, { text: '' }], correct: '0' })}>
          {t('campaigns.form.addQuestion')}
        </Button>
      </div>

      <Preview control={form.control} />

      {problem && (
        <Alert variant="destructive">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      <div className="flex gap-2">
        <Button type="submit" disabled={form.formState.isSubmitting}>{t('campaigns.form.save')}</Button>
        <Button type="button" variant="outline" onClick={onCancel}>{t('campaigns.form.cancel')}</Button>
      </div>
    </form>
  )
}

const LIMITS_TIME = { min: LIMITS.minTimeLimitSec, max: LIMITS.maxTimeLimitSec }
const LIMITS_PERCENT = { min: LIMITS.minPercent, max: LIMITS.maxPercent }

function Field({ id, label, hint, error, className, children }: {
  id: string
  label: string
  hint?: string
  error?: string
  className?: string
  children: React.ReactNode
}) {
  return (
    <div className={`flex flex-col gap-1 ${className ?? ''}`}>
      <Label htmlFor={id}>{label}</Label>
      {children}
      {hint && <p className="text-sm text-muted-foreground">{hint}</p>}
      {error && <p className="text-sm text-destructive" role="alert">{error}</p>}
    </div>
  )
}

function QuestionCard({ form, index, count, onRemove, onMove }: {
  form: UseFormReturn<FormValues>
  index: number
  count: number
  onRemove: () => void
  onMove: (to: number) => void
}) {
  const { t } = useTranslation(SHOPIFY)
  const options = useFieldArray({ control: form.control, name: `questions.${index}.options` })
  const correct = useWatch({ control: form.control, name: `questions.${index}.correct` })
  const errors = form.formState.errors.questions?.[index]
  const no = index + 1
  return (
    <Card data-testid="question">
      <CardHeader>
        <CardTitle className="text-base">{t('campaigns.form.question', { no })}</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        <Field id={`q${index}`} label={t('campaigns.form.stem')} error={errors?.stem?.message}>
          <Textarea id={`q${index}`} maxLength={LIMITS.stemLength}
            {...form.register(`questions.${index}.stem`, { required: t('campaigns.form.required') })} />
        </Field>
        <fieldset className="flex flex-col gap-2">
          <legend className="text-sm font-medium">{t('campaigns.form.options')}</legend>
          <RadioGroup value={correct}
            onValueChange={(value) => form.setValue(`questions.${index}.correct`, value, { shouldDirty: true })}
            aria-label={t('campaigns.form.correctOf', { no })}>
            {options.fields.map((option, i) => (
              <div key={option.id} className="flex items-center gap-2">
                <RadioGroupItem value={String(i)} id={`q${index}c${i}`}
                  aria-label={t('campaigns.form.correctOption', { option: i + 1 })} />
                <Input aria-label={t('campaigns.form.option', { option: i + 1 })} maxLength={LIMITS.optionLength}
                  {...form.register(`questions.${index}.options.${i}.text`, { required: t('campaigns.form.required') })} />
                <Button type="button" variant="ghost" size="sm" disabled={options.fields.length <= LIMITS.minOptions}
                  onClick={() => {
                    options.remove(i)
                    // The correct one stays the same option, or the first when it was removed.
                    const at = Number(correct)
                    form.setValue(`questions.${index}.correct`, String(at === i ? 0 : at > i ? at - 1 : at))
                  }}>
                  {t('campaigns.form.removeOption')}
                </Button>
              </div>
            ))}
          </RadioGroup>
          {errors?.options?.some?.((o) => o?.text) && (
            <p className="text-sm text-destructive" role="alert">{t('campaigns.form.optionsRequired')}</p>
          )}
          <div>
            <Button type="button" variant="outline" size="sm" disabled={options.fields.length >= LIMITS.maxOptions}
              onClick={() => options.append({ text: '' })}>
              {t('campaigns.form.addOption')}
            </Button>
          </div>
        </fieldset>
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="ghost" size="sm" disabled={index === 0} onClick={() => onMove(index - 1)}>
            {t('campaigns.form.up')}
          </Button>
          <Button type="button" variant="ghost" size="sm" disabled={index === count - 1}
            onClick={() => onMove(index + 1)}>
            {t('campaigns.form.down')}
          </Button>
          <Button type="button" variant="ghost" size="sm" disabled={count <= 1} onClick={onRemove}>
            {t('campaigns.form.removeQuestion')}
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

/** The quiz as the storefront's block shows it, from what is typed (nothing is sent). */
function Preview({ control }: { control: Control<FormValues> }) {
  const { t } = useTranslation(SHOPIFY)
  const values = useWatch({ control }) as Partial<FormValues>
  return (
    <Card data-testid="preview">
      <CardHeader>
        <CardTitle>{t('campaigns.preview.title')}</CardTitle>
        <CardDescription>{t('campaigns.preview.button', { percent: values.percent ?? 0 })}</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {(values.questions ?? []).map((question, index) => (
          <div key={index}>
            <p className="font-medium">{index + 1}. {question?.stem || '…'}</p>
            <ul className="ml-4 list-disc">
              {(question?.options ?? []).map((option, i) => <li key={i}>{option?.text || '…'}</li>)}
            </ul>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
