import { useMutation, useQuery } from '@tanstack/react-query'
import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import {
  ApiError,
  Badge,
  Button,
  Checkbox,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  PageHeader,
  PageState,
  paths,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  UserName,
  useAuth,
} from '@jabiz/admin'
import { EVERYBODY, failureRate, PARAM_LABELS, proposeSwitch, readAiJobs, readAiSettings, readAiUsage } from './ai-api'
import { LoadFailed, Section } from './components'
import { CONTROL, PERMISSION } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** The switch's state and a proposal to change it (four eyes: another administrator publishes it). */
function Switch({ on, exists }: { on: boolean; exists: boolean }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const [reason, setReason] = useState('')
  const [local, setLocal] = useState<string | null>(null)
  const propose = useMutation({ mutationFn: () => proposeSwitch(!on, reason.trim()) })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!reason.trim()) {
      setLocal(t('ai.reasonRequired'))
      return
    }
    setLocal(null)
    propose.mutate()
  }
  const proposer = can(PERMISSION.controlPropose)
  return (
    <div className="flex flex-col gap-3" data-testid="ai-switch" data-on={on}>
      <p className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{t('ai.params.enabled')}</span>
        <Badge variant={on ? 'success' : 'outline'} data-testid="ai-switch-state">{on ? t('ai.on') : t('ai.off')}</Badge>
        {!exists && <Badge variant="warning">{t('ai.notPublished')}</Badge>}
      </p>
      <p className="text-muted-foreground text-sm">{t('ai.changeHint')}</p>
      {proposer && !propose.isSuccess && (
        <form noValidate onSubmit={submit} className="flex flex-wrap items-end gap-2">
          <div className="flex min-w-64 flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('ai.reason')}</Label>
            <Input id={`${id}-reason`} value={reason} maxLength={500} data-testid="ai-switch-reason"
              aria-invalid={local ? true : undefined} aria-describedby={local ? `${id}-problem` : undefined}
              onChange={(event) => setReason(event.target.value)} />
          </div>
          <Button type="submit" variant={on ? 'destructive' : 'default'} disabled={propose.isPending}
            aria-busy={propose.isPending || undefined} data-testid="ai-switch-propose">
            {on ? t('ai.turnOff') : t('ai.turnOn')}
          </Button>
        </form>
      )}
      {local && <p id={`${id}-problem`} role="alert" className="text-destructive text-sm">{local}</p>}
      {propose.isError && (
        <p role="alert" className="text-destructive text-sm">
          {propose.error instanceof ApiError ? propose.error.display : t('common.failed')}
        </p>
      )}
      {propose.isSuccess && <p role="status" className="text-sm" data-testid="ai-switch-proposed">{t('ai.proposed')}</p>}
      {proposer || can(PERMISSION.controlPublish)
        ? <Link to={paths.dataset(CONTROL.changes)} className={linkClass}>{t('ai.changes')}</Link>
        : <p className="text-muted-foreground text-sm" data-testid="ai-no-control">{t('ai.noControl')}</p>}
    </div>
  )
}

/**
 * AI generation for the administrators (Q8a; `qb.admin.ai.read`): the switch and limits in force (changed only through
 * controlled changes), the usage by sponsor and month with failures and a dollar estimate, and the recent requests
 * with the model that answered and why they failed. Never the candidates: they are the sponsor's until reviewed.
 */
export default function AiPage() {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [month, setMonth] = useState('')
  const [failedOnly, setFailedOnly] = useState(false)
  const shownMonth = MONTH.test(month) ? month : null
  const settings = useQuery({ queryKey: ['qb-ext', 'ai', 'settings'], queryFn: readAiSettings })
  const usage = useQuery({ queryKey: ['qb-ext', 'ai', 'usage', shownMonth], queryFn: () => readAiUsage(shownMonth) })
  const jobs = useQuery({ queryKey: ['qb-ext', 'ai', 'jobs', failedOnly], queryFn: () => readAiJobs(failedOnly) })
  const n = (value: number) => value.toLocaleString(i18n.language)
  const label = (key: string) => (PARAM_LABELS[key] ? t(`ai.params.${PARAM_LABELS[key]}`) : key)

  let settingsBody: ReactNode
  if (settings.isError) settingsBody = <LoadFailed error={settings.error} onRetry={() => void settings.refetch()} />
  else if (settings.isPending) settingsBody = <PageState kind="loading" />
  else {
    const enabled = settings.data.find((s) => s.paramKey === 'qb.ai.enabled')
    settingsBody = (
      <>
        <Switch on={enabled?.value === 'true'} exists={enabled?.paramExists ?? false} />
        <Table aria-label={t('ai.settingsTable')} data-testid="ai-settings">
          <TableHeader>
            <TableRow>
              <TableHead>{t('ai.setting')}</TableHead>
              <TableHead>{t('ai.value')}</TableHead>
              <TableHead>{t('ai.key')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {settings.data.map((s) => (
              <TableRow key={s.paramKey} data-testid="ai-setting" data-key={s.paramKey}>
                <TableCell>{label(s.paramKey)}</TableCell>
                <TableCell>
                  {s.paramExists ? <code>{s.value}</code> : <Badge variant="warning">{t('ai.notPublished')}</Badge>}
                </TableCell>
                <TableCell><code className="text-xs">{s.paramKey}</code></TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </>
    )
  }

  let usageBody: ReactNode
  if (usage.isError) usageBody = <LoadFailed error={usage.error} onRetry={() => void usage.refetch()} />
  else if (usage.isPending) usageBody = <PageState kind="loading" />
  else if (usage.data.length === 0) usageBody = <p className="text-muted-foreground text-sm">{t('ai.noUsage')}</p>
  else {
    usageBody = (
      <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('ai.usageTable')}>
        <Table data-testid="ai-usage">
          <TableHeader>
            <TableRow>
              <TableHead>{t('ai.month')}</TableHead>
              <TableHead>{t('ai.sponsor')}</TableHead>
              <TableHead className="text-right">{t('ai.jobs')}</TableHead>
              <TableHead className="text-right">{t('ai.failureRate')}</TableHead>
              <TableHead className="text-right">{t('ai.inputTokens')}</TableHead>
              <TableHead className="text-right">{t('ai.outputTokens')}</TableHead>
              <TableHead className="text-right">{t('ai.estimate')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {usage.data.map((row) => {
              const rate = failureRate(row)
              return (
                <TableRow key={row.usageId} data-testid="ai-usage-row" data-sponsor={row.sponsorId}>
                  <TableCell>{row.month}</TableCell>
                  <TableCell>
                    {row.sponsorId === EVERYBODY ? <strong>{t('ai.everybody')}</strong>
                      : <>{row.company ?? ''} <UserName id={row.sponsorId} /></>}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n(row.jobs)}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {rate == null ? '' : `${(rate * 100).toFixed(1)}% (${n(row.failedJobs)})`}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">{n(row.inputTokens)}</TableCell>
                  <TableCell className="text-right tabular-nums">{n(row.outputTokens)}</TableCell>
                  <TableCell className="text-right tabular-nums">{row.estimatedUsd ?? ''}</TableCell>
                </TableRow>
              )
            })}
          </TableBody>
        </Table>
      </div>
    )
  }

  let jobsBody: ReactNode
  if (jobs.isError) jobsBody = <LoadFailed error={jobs.error} onRetry={() => void jobs.refetch()} />
  else if (jobs.isPending) jobsBody = <PageState kind="loading" />
  else if (jobs.data.length === 0) jobsBody = <p className="text-muted-foreground text-sm">{t('ai.noJobs')}</p>
  else {
    jobsBody = (
      <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('ai.recentTable')}>
        <Table data-testid="ai-jobs">
          <TableHeader>
            <TableRow>
              <TableHead>{t('ai.requestedAt')}</TableHead>
              <TableHead>{t('ai.sponsor')}</TableHead>
              <TableHead>{t('ai.kind')}</TableHead>
              <TableHead>{t('ai.status')}</TableHead>
              <TableHead>{t('ai.model')}</TableHead>
              <TableHead>{t('ai.servedModel')}</TableHead>
              <TableHead className="text-right">{t('ai.attempts')}</TableHead>
              <TableHead className="text-right">{t('ai.inputTokens')}</TableHead>
              <TableHead className="text-right">{t('ai.outputTokens')}</TableHead>
              <TableHead>{t('ai.failure')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {jobs.data.map((row) => (
              <TableRow key={row.aiJobId} data-testid="ai-job-row" data-status={row.status}>
                <TableCell className="whitespace-nowrap">{new Date(row.requestedAt).toLocaleString(i18n.language)}</TableCell>
                <TableCell><UserName id={row.sponsorId} /></TableCell>
                <TableCell data-testid="ai-job-kind">{t(`ai.kinds.${row.kind}`, { defaultValue: row.kind })}</TableCell>
                <TableCell>
                  <Badge variant={row.status === 'FAILED' ? 'destructive' : 'outline'}>
                    {t(`ai.statuses.${row.status}`, { defaultValue: row.status })}
                  </Badge>
                </TableCell>
                <TableCell><code className="text-xs">{row.model}</code></TableCell>
                <TableCell>
                  {row.servedModel && row.servedModel !== row.model
                    ? <Badge variant="warning"><code className="text-xs">{row.servedModel}</code></Badge>
                    : <code className="text-xs">{row.servedModel ?? ''}</code>}
                </TableCell>
                <TableCell className="text-right tabular-nums">{n(row.attempts)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.inputTokens)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.outputTokens)}</TableCell>
                <TableCell className="text-xs">
                  {row.failureCode && <><code>{row.failureCode}</code> {row.failureMessage}</>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="qb-ai">
      <PageHeader title={t('ai.title')} description={t('ai.description')} className="pb-0" />
      <Section title={t('ai.switch')} testId="ai-switch-section">{settingsBody}</Section>
      <Section title={t('ai.usage')} testId="ai-usage-section" actions={
        <div className="flex items-center gap-2">
          <Label htmlFor={`${id}-month`}>{t('ai.month')}</Label>
          <Input id={`${id}-month`} type="month" className="w-44" value={month} data-testid="ai-month"
            onChange={(event) => setMonth(event.target.value)} />
        </div>
      }>{usageBody}</Section>
      <Section title={t('ai.recent')} testId="ai-jobs-section" actions={
        <div className="flex items-center gap-2">
          <Checkbox id={`${id}-failed`} checked={failedOnly} data-testid="ai-failed-only"
            onCheckedChange={(checked) => setFailedOnly(checked === true)} />
          <Label htmlFor={`${id}-failed`}>{t('ai.failedOnly')}</Label>
        </div>
      }>{jobsBody}</Section>
    </div>
  )
}
