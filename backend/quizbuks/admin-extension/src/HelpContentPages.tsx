import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
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
} from '@jabiz/admin'
import { EmptyState, LoadFailed, Section } from './components'
import {
  changedMeanwhile,
  HELP_LANGUAGES,
  loadHelpManual,
  publishHelp,
  readHelpGaps,
  readHelpManuals,
  readHelpMaterials,
  readHelpUsage,
  refusedWith,
  type HelpManual,
} from './help-api'
import { when } from './hooks'
import { HELP, PATH } from './ids'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** This month (UTC), `YYYY-MM`. */
function thisMonth(now = new Date()): string {
  return now.toISOString().slice(0, 7)
}

/**
 * What the AI help did not solve in a month (F-C10c; `qb.admin.help-gaps`, content administrators): unsolved answers
 * and conversations handed over, with the page and the question (never who asked), to add to the FAQ and the manuals.
 * The month is in the address (`?month=`).
 */
export function HelpGapsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const [search, setSearch] = useSearchParams()
  const asked = search.get('month') ?? ''
  const month = MONTH.test(asked) ? asked : thisMonth()
  const gaps = useQuery({ queryKey: ['qb-ext', 'help', 'gaps', month], queryFn: () => readHelpGaps(month) })

  let body: ReactNode
  if (gaps.isError) body = <LoadFailed error={gaps.error} onRetry={() => void gaps.refetch()} />
  else if (gaps.isPending) body = <PageState kind="loading" />
  else if (gaps.data.length === 0) body = <EmptyState title={t('help.gaps.none')} testId="help-gaps-none" />
  else {
    body = (
      <>
        <p className="text-muted-foreground text-sm" data-testid="help-gaps-count">
          {t('help.gaps.count', { count: gaps.data.length })}</p>
        <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('help.gaps.table')}>
          <Table data-testid="help-gaps">
            <TableHeader>
              <TableRow>
                <TableHead>{t('help.gaps.columns.at')}</TableHead>
                <TableHead>{t('help.gaps.columns.reason')}</TableHead>
                <TableHead>{t('help.gaps.columns.entry')}</TableHead>
                <TableHead>{t('help.gaps.columns.language')}</TableHead>
                <TableHead>{t('help.gaps.columns.page')}</TableHead>
                <TableHead>{t('help.gaps.columns.question')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {gaps.data.map((gap) => (
                <TableRow key={gap.rowKey} data-testid="help-gap" data-reason={gap.reason}>
                  <TableCell className="whitespace-nowrap">{when(gap.at)}</TableCell>
                  <TableCell>
                    <Badge variant={gap.reason === 'ESCALATED' ? 'secondary' : 'outline'}>
                      {t(`help.gaps.reasons.${gap.reason}`, { defaultValue: gap.reason })}</Badge>
                  </TableCell>
                  <TableCell>{t(`help.entries.${gap.entry}`, { defaultValue: gap.entry })}</TableCell>
                  <TableCell>{gap.language ?? ''}</TableCell>
                  <TableCell><code className="text-xs">{gap.page ?? ''}</code></TableCell>
                  {/* The question as the user wrote it: plain text. */}
                  <TableCell className="max-w-md text-sm break-words whitespace-pre-wrap">{gap.question ?? ''}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      </>
    )
  }

  return (
    <div className="flex flex-col gap-4" data-testid="qb-help-gaps">
      <PageHeader title={t('help.gaps.title')} description={t('help.gaps.description')} className="pb-0" />
      <div className="flex items-center gap-2">
        <Label htmlFor={`${id}-month`}>{t('help.gaps.month')}</Label>
        <Input id={`${id}-month`} type="month" className="w-44" value={month} data-testid="help-gaps-month"
          onChange={(event) => {
            if (MONTH.test(event.target.value)) setSearch({ month: event.target.value }, { replace: true })
          }} />
      </div>
      {body}
    </div>
  )
}

/**
 * The AI help's usage by month on headquarters' AI page (F-C10c; `qb.admin.help-usage`), apart from the Sponsors' AI
 * usage: questions, people, failures, the tokens with what the prompt cache read and wrote, a dollar estimate and the
 * share of the monthly cap used.
 */
export function HelpUsageSection({ month }: { month: string | null }) {
  const { t, i18n } = useTranslation(EXTENSION_NAMESPACE)
  const usage = useQuery({ queryKey: ['qb-ext', 'help', 'usage', month], queryFn: () => readHelpUsage(month) })
  const n = (value: number) => value.toLocaleString(i18n.language)
  let body: ReactNode
  if (usage.isError) body = <LoadFailed error={usage.error} onRetry={() => void usage.refetch()} />
  else if (usage.isPending) body = <PageState kind="loading" />
  else if (usage.data.length === 0) body = <p className="text-muted-foreground text-sm">{t('help.usage.none')}</p>
  else {
    body = (
      <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('help.usage.table')}>
        <Table data-testid="help-usage">
          <TableHeader>
            <TableRow>
              <TableHead>{t('help.usage.columns.month')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.questions')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.people')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.failed')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.cancelled')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.input')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.cacheRead')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.cacheWrite')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.output')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.estimate')}</TableHead>
              <TableHead className="text-right">{t('help.usage.columns.used')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {usage.data.map((row) => (
              <TableRow key={row.month} data-testid="help-usage-row" data-month={row.month}>
                <TableCell>{row.month}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.questions)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.people)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.failedJobs)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.cancelledJobs)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.inputTokens)}</TableCell>
                <TableCell className="text-right tabular-nums" data-testid="help-usage-cache-read">
                  {n(row.cacheReadTokens)}</TableCell>
                <TableCell className="text-right tabular-nums" data-testid="help-usage-cache-write">
                  {n(row.cacheWriteTokens)}</TableCell>
                <TableCell className="text-right tabular-nums">{n(row.outputTokens)}</TableCell>
                <TableCell className="text-right tabular-nums">{row.estimatedUsd ?? ''}</TableCell>
                <TableCell className="text-right tabular-nums" data-testid="help-usage-percent">
                  {row.percentUsed == null ? '' : t('help.usage.used', {
                    percent: row.percentUsed.toLocaleString(i18n.language, { minimumFractionDigits: 1,
                      maximumFractionDigits: 1 }),
                    tokens: n(row.monthlyTokens) })}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </div>
    )
  }
  return (
    <Section title={t('help.usage.title')} testId="help-usage-section">
      <p className="text-muted-foreground text-sm">{t('help.usage.description')}</p>
      {body}
    </Section>
  )
}

/** One entry's manual: its state, the draft by language (plain text), publishing and loading the repository's text. */
function ManualCard({ manual }: { manual: HelpManual }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const client = useQueryClient()
  const [message, setMessage] = useState<string | null>(null)
  const [problem, setProblem] = useState<string | null>(null)
  const [edited, setEdited] = useState(false)
  const reload = () => void client.invalidateQueries({ queryKey: ['qb-ext', 'help', 'manuals'] })
  const failed = (error: unknown) => {
    setMessage(null)
    if (changedMeanwhile(error)) {
      setProblem(t('help.manuals.conflict'))
      reload()
    } else if (refusedWith(error, HELP.manualEdited)) {
      setProblem(null)
      setEdited(true)
    } else {
      setProblem(error instanceof ApiError ? error.display : t('common.failed'))
    }
  }
  const publish = useMutation({ mutationFn: () => publishHelp(manual), onSuccess: () => {
    setProblem(null)
    setMessage(t('help.manuals.published'))
    reload()
  }, onError: failed })
  const load = useMutation({ mutationFn: (choice: 'keepDraft' | 'replace' | null) => loadHelpManual(manual, choice),
    onSuccess: (output) => {
      setProblem(null)
      setEdited(false)
      setMessage(output.replaced ? t('help.manuals.loaded') : t('help.manuals.kept'))
      reload()
    }, onError: failed })
  const missing = HELP_LANGUAGES.filter((language) => !manual.body[language]?.trim())
  const differs = JSON.stringify(HELP_LANGUAGES.map((l) => manual.body[l] ?? ''))
    !== JSON.stringify(HELP_LANGUAGES.map((l) => manual.publishedBody[l] ?? ''))
  const busy = publish.isPending || load.isPending
  return (
    <Section title={t(`help.entries.${manual.entry}`, { defaultValue: manual.entry })}
      testId="help-manual">
      <div className="flex flex-col gap-1 text-sm" data-entry={manual.entry} data-testid="help-manual-state">
        <span>{t('help.manuals.revision', { revision: manual.revision })}</span>
        <span>{manual.publishedAt ? t('help.manuals.publishedAt', { time: when(manual.publishedAt) })
          : t('help.manuals.neverPublished')}</span>
        <span>{differs ? t('help.manuals.unpublished') : t('help.manuals.upToDate')}</span>
        {missing.length > 0 && (
          <span className="text-destructive">{t('help.manuals.missing', {
            languages: missing.map((l) => t(`help.manuals.languages.${l}`)).join(', ') })}</span>
        )}
      </div>
      {HELP_LANGUAGES.map((language) => (
        <details key={language} className="rounded-md border">
          <summary className="cursor-pointer p-2 text-sm font-medium">
            {t('help.manuals.draft', { language: t(`help.manuals.languages.${language}`) })}</summary>
          {/* The draft as text: it is Markdown for the AI, never rendered here. */}
          <pre className="max-h-80 overflow-auto border-t p-2 text-xs whitespace-pre-wrap" lang={language}>
            {manual.body[language] ?? ''}</pre>
        </details>
      ))}
      {message && <p role="status" className="text-sm" data-testid="help-manual-done">{message}</p>}
      {problem && (
        <Alert variant="destructive" data-testid="help-manual-problem">
          <AlertDescription>{problem}</AlertDescription>
        </Alert>
      )}
      {edited && (
        <Alert variant="warning" data-testid="help-manual-edited">
          <AlertDescription className="flex flex-col gap-2">
            <span>{t('help.manuals.edited')}</span>
            <span className="flex flex-wrap gap-2">
              <Button type="button" size="sm" variant="outline" disabled={busy} data-testid="help-manual-keep"
                onClick={() => load.mutate('keepDraft')}>{t('help.manuals.keepDraft')}</Button>
              <Button type="button" size="sm" variant="destructive" disabled={busy} data-testid="help-manual-replace"
                onClick={() => load.mutate('replace')}>{t('help.manuals.replace')}</Button>
            </span>
          </AlertDescription>
        </Alert>
      )}
      <div className="flex flex-wrap gap-2">
        <Button type="button" disabled={busy || missing.length > 0} aria-busy={publish.isPending || undefined}
          data-testid="help-manual-publish" onClick={() => publish.mutate()}>{t('help.manuals.publish')}</Button>
        <Button type="button" variant="outline" disabled={busy} aria-busy={load.isPending || undefined}
          data-testid="help-manual-load" onClick={() => load.mutate(null)}>{t('help.manuals.load')}</Button>
      </div>
    </Section>
  )
}

/**
 * The AI help's usage manuals (F-C10c; content administrators): the drafts are edited on the platform's generic
 * pages (`QbHelpManual`); here they are published (with the draft's version read), loaded from the repository's text
 * (choosing to keep or replace a draft edited here), and the materials rebuilt after editing the FAQ. Also the
 * snapshots in use and their size.
 */
export function HelpManualsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const client = useQueryClient()
  const manuals = useQuery({ queryKey: ['qb-ext', 'help', 'manuals'], queryFn: readHelpManuals })
  const materials = useQuery({ queryKey: ['qb-ext', 'help', 'manuals', 'materials'], queryFn: readHelpMaterials })
  const [rebuilt, setRebuilt] = useState(false)
  const rebuild = useMutation({ mutationFn: () => publishHelp(null), onSuccess: () => {
    setRebuilt(true)
    void client.invalidateQueries({ queryKey: ['qb-ext', 'help', 'manuals'] })
  } })
  const kb = (bytes: number) => t('help.manuals.bytes', { kb: (bytes / 1024).toFixed(1) })

  let body: ReactNode
  if (manuals.isError) body = <LoadFailed error={manuals.error} onRetry={() => void manuals.refetch()} />
  else if (manuals.isPending) body = <PageState kind="loading" />
  else body = manuals.data.map((manual) => <ManualCard key={`${manual.manualId}:${manual.revision}`} manual={manual} />)

  return (
    <div className="flex flex-col gap-4" data-testid="qb-help-manuals">
      <PageHeader title={t('help.manuals.title')} description={t('help.manuals.description')} className="pb-0"
        actions={<Link to={paths.dataset(HELP.manual)} className={linkClass}>{t('help.manuals.edit')}</Link>} />
      {body}
      <Section title={t('help.manuals.materials')} testId="help-materials-section" actions={
        <Button type="button" variant="outline" size="sm" disabled={rebuild.isPending} data-testid="help-rebuild"
          onClick={() => rebuild.mutate()}>{t('help.manuals.rebuild')}</Button>
      }>
        <p className="text-muted-foreground text-sm">{t('help.manuals.rebuildHint')}</p>
        {rebuilt && <p role="status" className="text-sm">{t('help.manuals.rebuilt')}</p>}
        {rebuild.isError && (
          <p role="alert" className="text-destructive text-sm">
            {rebuild.error instanceof ApiError ? rebuild.error.display : t('common.failed')}</p>
        )}
        {materials.isError && <LoadFailed error={materials.error} onRetry={() => void materials.refetch()} />}
        {materials.isSuccess && (
          <div className="overflow-x-auto" tabIndex={0} role="region" aria-label={t('help.manuals.materialsTable')}>
            <Table data-testid="help-materials">
              <TableHeader>
                <TableRow>
                  <TableHead>{t('help.manuals.materialColumns.entry')}</TableHead>
                  <TableHead>{t('help.manuals.materialColumns.language')}</TableHead>
                  <TableHead className="text-right">{t('help.manuals.materialColumns.bytes')}</TableHead>
                  <TableHead>{t('help.manuals.materialColumns.latest')}</TableHead>
                  <TableHead>{t('help.manuals.materialColumns.state')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {materials.data.map((row) => (
                  <TableRow key={`${row.entry}/${row.language}`} data-testid="help-material">
                    <TableCell>{t(`help.entries.${row.entry}`, { defaultValue: row.entry })}</TableCell>
                    <TableCell>{row.language}</TableCell>
                    <TableCell className="text-right tabular-nums">{row.bytes == null ? '' : kb(row.bytes)}</TableCell>
                    <TableCell className="whitespace-nowrap">{kb(row.latestBytes)} {when(row.latestAt)}</TableCell>
                    <TableCell>{row.pending ? <Badge variant="secondary">{t('help.manuals.pending')}</Badge> : ''}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
      </Section>
    </div>
  )
}

/** The link from the AI page to the gaps of the month shown (or this month). */
export function HelpGapsLink({ month }: { month: string | null }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Link to={`${PATH.helpGaps}?month=${month ?? thisMonth()}`} className={linkClass}
    data-testid="help-gaps-link">{t('help.gaps.title')}</Link>
}
