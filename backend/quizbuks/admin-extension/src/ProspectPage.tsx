import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  Checkbox,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  PageState,
  RadioGroup,
  RadioGroupItem,
  Textarea,
} from '@jabiz/admin'
import { LoadFailed, Section } from './components'
import { useLabels, when } from './hooks'
import { DICTIONARY, PATH } from './ids'
import { approve, discard, DIRECT, PROSPECTS_KEY, readProspect, type Prospect, type Trial } from './prospect-api'
import { useProspectScope } from './prospect-scope'

const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** A yes, a no, or that the site does not say. */
function YesNo({ value }: { value: boolean | null }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <>{value == null ? t('prospect.detail.unknown') : t(value ? 'prospect.detail.yes' : 'prospect.detail.no')}</>
}

/** What the research found: the facts the person checks the draft against. */
function Research({ prospect }: { prospect: Prospect }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const rows: [string, React.ReactNode][] = [
    [t('prospect.detail.website'), (
      <a href={/^https?:\/\//.test(prospect.websiteUrl) ? prospect.websiteUrl : undefined} target="_blank"
        rel="noreferrer noopener" className="text-primary underline-offset-4 hover:underline">
        {prospect.websiteUrl}
      </a>
    )],
    [t('prospect.detail.email'), prospect.publicEmail ?? t('prospect.detail.unknown')],
    [t('prospect.detail.language'), prospect.language ?? t('prospect.detail.unknown')],
    [t('prospect.detail.country'), prospect.country ?? t('prospect.detail.unknown')],
    [t('prospect.detail.sellsOnline'), <YesNo key="o" value={prospect.sellsOnline} />],
    [t('prospect.detail.shopify'), <YesNo key="s" value={prospect.shopify} />],
    [t('prospect.detail.refuses'), <YesNo key="r" value={prospect.refusesMarketing} />],
  ]
  return (
    <Section title={t('prospect.detail.research')} testId="prospect-research">
      <dl className="grid gap-x-6 gap-y-2 sm:grid-cols-[max-content_1fr]">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-muted-foreground text-sm">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
      {prospect.failure && <p data-testid="prospect-failure">{t('prospect.detail.failure')}: {prospect.failure}</p>}
      {prospect.scoreReasons.length > 0 && (
        <div className="flex flex-col gap-1">
          <h3 className="font-medium">{t('prospect.detail.reasons')}</h3>
          <ul className="list-disc ps-5">{prospect.scoreReasons.map((reason) => <li key={reason}>{reason}</li>)}</ul>
        </div>
      )}
      {prospect.products.length > 0 && (
        <div className="flex flex-col gap-1">
          <h3 className="font-medium">{t('prospect.detail.products')}</h3>
          <ul className="list-disc ps-5">
            {prospect.products.map((product) => (
              <li key={product.name}><span className="font-medium">{product.name}</span>
                {product.description ? ` — ${product.description}` : ''}</li>
            ))}
          </ul>
        </div>
      )}
    </Section>
  )
}

/** The trial quiz's fields: a title, an introduction, five questions of four options with the right one chosen. */
function TrialFields({ id, trial, onChange }: { id: string; trial: Trial; onChange: (trial: Trial) => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const setQuestion = (index: number, change: Partial<Trial['questions'][number]>) => onChange({
    ...trial, questions: trial.questions.map((q, i) => (i === index ? { ...q, ...change } : q)),
  })
  return (
    <div className="flex flex-col gap-4" data-testid="prospect-trial">
      <p className="text-muted-foreground text-sm">{t('prospect.detail.trialHint')}</p>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-title`}>{t('prospect.detail.trialTitle')}</Label>
        <Input id={`${id}-title`} value={trial.title} maxLength={120} data-testid="trial-title"
          onChange={(event) => onChange({ ...trial, title: event.target.value })} />
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-intro`}>{t('prospect.detail.intro')}</Label>
        <Input id={`${id}-intro`} value={trial.intro ?? ''} maxLength={300}
          onChange={(event) => onChange({ ...trial, intro: event.target.value })} />
      </div>
      {trial.questions.map((question, qi) => {
        const right = question.options.findIndex((option) => option.correct)
        return (
          <fieldset key={qi} className="flex flex-col gap-2 rounded-md border p-3" data-testid="trial-question">
            <legend className="px-1 font-medium">{t('prospect.detail.question', { n: qi + 1 })}</legend>
            <Textarea aria-label={t('prospect.detail.question', { n: qi + 1 })} value={question.stem} maxLength={300}
              onChange={(event) => setQuestion(qi, { stem: event.target.value })} />
            <RadioGroup value={right < 0 ? '' : String(right)} aria-label={t('prospect.detail.correct')}
              onValueChange={(value) => setQuestion(qi, { options: question.options.map((option, oi) => (
                { ...option, correct: String(oi) === value })) })}>
              {question.options.map((option, oi) => (
                <div key={oi} className="flex items-center gap-2">
                  <RadioGroupItem value={String(oi)} id={`${id}-q${qi}-o${oi}`}
                    aria-label={`${t('prospect.detail.correct')}: ${t('prospect.detail.option', { n: oi + 1 })}`} />
                  <Input id={`${id}-q${qi}-o${oi}-text`} value={option.text} maxLength={150}
                    aria-label={t('prospect.detail.option', { n: oi + 1 })}
                    onChange={(event) => setQuestion(qi, { options: question.options.map((o, i) => (
                      i === oi ? { ...o, text: event.target.value } : o)) })} />
                </div>
              ))}
            </RadioGroup>
          </fieldset>
        )
      })}
    </div>
  )
}

/** The draft: the mail and the trial quiz, edited and approved with the revision the page read. */
function Draft({ prospect, writable }: { prospect: Prospect; writable: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [subject, setSubject] = useState(prospect.mailSubject ?? '')
  const [body, setBody] = useState(prospect.mailBody ?? '')
  const [trial, setTrial] = useState<Trial | null>(prospect.trial)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !subject.trim() || !body.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await approve(prospect.prospectId, subject.trim(), body.trim(), trial, prospect.revision)
      notify.success(t('prospect.detail.approved'))
      await queryClient.invalidateQueries({ queryKey: PROSPECTS_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Section title={t('prospect.detail.draft')} testId="prospect-draft">
      <form noValidate className="flex max-w-3xl flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <p className="text-muted-foreground text-sm">{t('prospect.detail.draftHint')}</p>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-subject`}>{t('prospect.detail.subject')}</Label>
          <Input id={`${id}-subject`} value={subject} maxLength={120} required readOnly={!writable}
            data-testid="draft-subject" onChange={(event) => setSubject(event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-body`}>{t('prospect.detail.body')}</Label>
          <Textarea id={`${id}-body`} value={body} maxLength={1500} rows={8} required readOnly={!writable}
            data-testid="draft-body" onChange={(event) => setBody(event.target.value)} />
        </div>
        {trial && (
          <>
            <h3 className="font-medium">{t('prospect.detail.trial')}</h3>
            <TrialFields id={id} trial={trial} onChange={setTrial} />
          </>
        )}
        {problem && <Alert variant="destructive" data-testid="draft-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
        {writable && (
          <div className="flex flex-wrap gap-2">
            <Button type="submit" disabled={busy || !subject.trim() || !body.trim()} aria-busy={busy || undefined}
              data-testid="draft-approve">{t('prospect.detail.approve')}</Button>
            <Button type="button" variant="outline" data-testid="draft-discard"
              onClick={() => setDiscarding(true)}>{t('prospect.detail.discard')}</Button>
          </div>
        )}
      </form>
      {discarding && <DiscardDialog prospect={prospect} onClose={() => setDiscarding(false)} />}
    </Section>
  )
}

/** Discards a business (`QB_PROSPECT_DISCARD`), blocking its address and domain for everyone when asked. */
function DiscardDialog({ prospect, onClose }: { prospect: Prospect; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [block, setBlock] = useState(false)
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    setBusy(true)
    setProblem(null)
    try {
      await discard(prospect.prospectId, block, note.trim() || null)
      notify.success(t('prospect.detail.discarded'))
      await queryClient.invalidateQueries({ queryKey: PROSPECTS_KEY })
      onClose()
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  const name = prospect.company ?? prospect.domain
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="discard-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('prospect.detail.discardTitle', { name })}</DialogTitle>
            <DialogDescription>{t('prospect.detail.discardHint')}</DialogDescription>
          </DialogHeader>
          <div className="flex items-center gap-2">
            <Checkbox id={`${id}-block`} checked={block} data-testid="discard-block"
              onCheckedChange={(value) => setBlock(value === true)} />
            <Label htmlFor={`${id}-block`} className="font-normal">{t('prospect.detail.block')}</Label>
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-note`}>{t('prospect.detail.note')}</Label>
            <Textarea id={`${id}-note`} value={note} maxLength={500} onChange={(event) => setNote(event.target.value)} />
          </div>
          {problem && <Alert variant="destructive"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('prospect.cancel')}</Button>
            <Button type="submit" variant="destructive" disabled={busy} aria-busy={busy || undefined}
              data-testid="discard-submit">{t('prospect.detail.discard')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * One business AI prospecting found (Q48c): what its research found, and, while it is a draft, the mail and the trial
 * quiz to edit and approve, or to discard. Only the owner's own drafts are decided here: headquarters reading an
 * operator's sees them without the buttons.
 */
export default function ProspectPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { prospectId = '' } = useParams()
  const scope = useProspectScope()
  const statusLabel = useLabels(DICTIONARY.prospectStatus)
  const prospect = useQuery({ queryKey: [...PROSPECTS_KEY, scope, 'one', prospectId],
    queryFn: () => readProspect(scope, prospectId) })
  const back = PATH.prospects + (scope.admin ? `?owner=${encodeURIComponent(scope.owner)}` : '')
  const header = (
    <Button variant="ghost" size="sm" asChild className="w-fit">
      <Link to={back}><ArrowLeft aria-hidden />{t('prospect.detail.back')}</Link>
    </Button>
  )
  if (prospect.isError) return <div className="flex flex-col gap-4">{header}<LoadFailed error={prospect.error} /></div>
  if (prospect.isPending) return <PageState kind="loading" />
  const p = prospect.data
  if (!p) return <div className="flex flex-col gap-4">{header}<p>{t('prospect.detail.notFound')}</p></div>
  const own = !scope.admin || scope.owner === DIRECT
  return (
    <div className="flex flex-col gap-4" data-testid="prospect-page">
      {header}
      <PageHeader title={p.company ?? p.domain} description={p.domain} className="pb-0"
        actions={(
          <span className="flex items-center gap-2">
            {p.score != null && <Badge variant="outline">{t('prospect.list.score')} {p.score}</Badge>}
            <Badge variant="secondary" data-testid="prospect-status">{statusLabel(p.status)}</Badge>
          </span>
        )} />
      <Research prospect={p} />
      {p.status === 'DRAFTED'
        ? <Draft key={p.revision} prospect={p} writable={own} />
        : (
          <p className="text-muted-foreground" data-testid="prospect-read-only">
            {t('prospect.detail.readOnly', { status: statusLabel(p.status) })}
            {p.sentAt ? ` ${when(p.sentAt)}` : ''}
          </p>
        )}
    </div>
  )
}
