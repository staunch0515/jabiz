import { api, unwrap, ApiError, useAuth, UserName, formatDateTime } from '@jabiz/client'
import {
  Badge,
  Button,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Label,
  notify,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  Spinner,
  Textarea,
  UI_NAMESPACE,
  type ColumnDef,
} from '@jabiz/ui'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { UNDONE, type FailingItem, type Job, type JobRun } from '../api/jobs'
import AdminPage from '../layout/AdminPage'

const OUTCOME_VARIANTS: Record<string, 'success' | 'secondary' | 'destructive' | 'warning'> = {
  SUCCEEDED: 'success', REPLAYED: 'secondary', FAILED: 'destructive', PARTIAL: 'warning',
}
const MAX_REASON = 2000
/** How often the list is asked for again while a run is under way or awaited. */
const POLL_MS = 2000
/** How long a re-run is awaited before the page stops asking and says it may still be running. */
export const PENDING_TIMEOUT_MS = 5 * 60_000

/** A re-run asked for, awaited until a run naming its request shows. */
interface Pending {
  job: string
  scheduledTime: string
  requestSeqId: number
  /** When it was asked for (Date.now()). */
  since: number
}

const fail = (e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e))

/** A titled card of the page. */
function Part({ title, children }: { title: ReactNode; children: ReactNode }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          <h2 className="text-base font-semibold">{title}</h2>
        </CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">{children}</CardContent>
    </Card>
  )
}

function Outcome({ outcome }: { outcome?: string }) {
  const { t } = useTranslation()
  return <Badge variant={OUTCOME_VARIANTS[outcome ?? ''] ?? 'secondary'}>{t(`jobs.outcomes.${outcome}`)}</Badge>
}

/** A per-item run's counts in one line. */
function ItemCounts({ run }: { run: JobRun }) {
  const { t } = useTranslation()
  if (!run.items) return null
  return (
    <span className="text-muted-foreground text-xs">
      {t('jobs.itemCounts', { ...run.items })}
      {run.items.more ? ` · ${t('jobs.more')}` : ''}
    </span>
  )
}

/**
 * The scheduled jobs (docs/design/11-ledger-events-jobs.md section 4; decision D77): their schedule and latest runs
 * (job.read), and a re-run of a past scheduled time by hand with the reason (job.run). A re-run starts at once on the
 * server; the list is asked for again until its run shows. The server checks every permission again; a step-up of
 * the second factor is asked for by the API client.
 */
export default function JobsPage() {
  const { t } = useTranslation()
  const { can } = useAuth()
  const queryClient = useQueryClient()
  // The re-runs awaited: in a ref for the list's answers, in state for what the page shows.
  const pendingRef = useRef<Pending[]>([])
  const [pending, setPending] = useState<Pending[]>([])
  // The job in the runs sheet and in the re-run dialog; kept while they close, so focus returns to their button. A
  // dialog opened again starts afresh (its key).
  const [shown, setShown] = useState<{ job: string; open: boolean } | null>(null)
  const [rerun, setRerun] = useState<{ job: string; open: boolean; opened: number } | null>(null)

  const jobs = useQuery({
    queryKey: ['jobs'],
    queryFn: async () => {
      const list = await unwrap(api.GET('/api/jobs'))
      await settle()
      return list
    },
    refetchInterval: (query) =>
      pending.length > 0 || (query.state.data ?? []).some((job) => job.running) ? POLL_MS : false,
  })

  /**
   * A re-run is over once the run naming its request shows (asked for by the request, whatever its scheduled time):
   * says how it ended. One awaited too long is no longer asked for, with a neutral note.
   */
  async function settle() {
    const settled: Pending[] = []
    for (const p of pendingRef.current) {
      let run: JobRun | undefined
      try {
        run = (await unwrap(api.GET('/api/jobs/{job}/runs', {
          params: { path: { job: p.job }, query: { requestSeqId: p.requestSeqId } },
        })))[0]
      } catch {
        // Asked again at the next poll.
      }
      const time = formatDateTime(p.scheduledTime)
      if (run) {
        const text = t('jobs.rerunDone', { job: p.job, time, outcome: t(`jobs.outcomes.${run.outcome}`) })
        if (UNDONE.has(run.outcome ?? '')) notify.error(text)
        else notify.success(text)
        settled.push(p)
      } else if (Date.now() - p.since > PENDING_TIMEOUT_MS) {
        notify.info(t('jobs.rerunStillRunning', { job: p.job, time }))
        settled.push(p)
      }
    }
    if (settled.length === 0) return
    pendingRef.current = pendingRef.current.filter((p) => !settled.includes(p))
    setPending(pendingRef.current)
  }

  const columns = useMemo<ColumnDef<Job, unknown>[]>(() => [
    {
      id: 'name',
      header: t('jobs.name'),
      cell: ({ row: { original: job } }) => (
        <span className="flex flex-col">
          <span className="font-medium">{job.name}</span>
          <span className="text-muted-foreground text-xs">{`${job.processName} v${job.processVersion}`}</span>
        </span>
      ),
    },
    {
      id: 'kind',
      header: t('jobs.kind'),
      cell: ({ row: { original: job } }) => <Badge variant="outline">{t(`jobs.kinds.${job.kind}`)}</Badge>,
    },
    {
      id: 'schedule',
      header: t('jobs.schedule'),
      cell: ({ row: { original: job } }) => (
        <span className="flex flex-col">
          <code className="font-mono text-xs">{`${job.cron} (${job.zone})`}</code>
          <span className="text-muted-foreground text-xs">
            {job.nextTime ? t('jobs.next', { time: formatDateTime(job.nextTime) }) : t('jobs.never')}
          </span>
        </span>
      ),
    },
    {
      id: 'last',
      header: t('jobs.lastRun'),
      cell: ({ row: { original: job } }) => {
        const run = job.recentRuns?.[0]
        return (
          <span className="flex flex-col items-start gap-1" data-testid={`job-last-${job.name}`}>
            <span className="flex flex-wrap items-center gap-1">
              {job.running && (
                <Badge variant="outline" data-testid={`job-running-${job.name}`}>
                  <Spinner className="size-3" />
                  {t('jobs.running')}
                </Badge>
              )}
              {run ? <Outcome outcome={run.outcome} /> : (
                <span className="text-muted-foreground text-sm">{t('jobs.noRuns')}</span>
              )}
              {run && <span className="text-sm">{formatDateTime(run.scheduledTime)}</span>}
            </span>
            {run && <ItemCounts run={run} />}
          </span>
        )
      },
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('list.actions')}</span>,
      meta: { pinned: 'right' },
      cell: ({ row: { original: job } }) => (
        <span className="flex gap-2">
          <Button variant="outline" size="sm" onClick={() => setShown({ job: job.name ?? '', open: true })}
            data-testid={`job-runs-${job.name}`}>
            {t('jobs.runs')}
          </Button>
          {can('job.run') && (
            <Button variant="outline" size="sm" onClick={() => setRerun((r) => ({ job: job.name ?? '', open: true, opened: (r?.opened ?? 0) + 1 }))}
              disabled={job.running || pending.some((p) => p.job === job.name)}
              data-testid={`job-rerun-${job.name}`}>
              {t('jobs.rerun')}
            </Button>
          )}
        </span>
      ),
    },
  ], [t, can, pending])

  const shownJob = jobs.data?.find((job) => job.name === shown?.job)
  const rerunJob = jobs.data?.find((job) => job.name === rerun?.job)
  return (
    <AdminPage title={t('jobs.title')}>
      <Part title={t('jobs.list')}>
        <div data-testid="jobs-list">
          <DataTable
            label={t('jobs.list')}
            columns={columns}
            getRowId={(job) => job.name ?? ''}
            loading={jobs.isLoading}
            data={jobs.data ?? []}
          />
        </div>
      </Part>
      <Sheet open={shown?.open ?? false} onOpenChange={(open) => !open && setShown((s) => s && { ...s, open: false })}>
        <SheetContent className="w-full overflow-y-auto sm:max-w-4xl" data-testid="job-runs">
          <SheetHeader>
            <SheetTitle>{shownJob ? t('jobs.runsTitle', { job: shownJob.name }) : ''}</SheetTitle>
            <SheetDescription className="sr-only">{t('jobs.runs')}</SheetDescription>
          </SheetHeader>
          {shownJob && <JobRuns job={shownJob} />}
        </SheetContent>
      </Sheet>
      {rerunJob && (
        <RerunDialog
          key={`${rerunJob.name}-${rerun?.opened}`}
          job={rerunJob}
          open={rerun?.open ?? false}
          onClose={() => setRerun((r) => r && { ...r, open: false })}
          onStarted={(started) => {
            pendingRef.current = [...pendingRef.current, started]
            setPending(pendingRef.current)
            void queryClient.invalidateQueries({ queryKey: ['jobs'] })
          }}
        />
      )}
    </AdminPage>
  )
}

/** A job's latest runs and, for a per-item job, its failing items. */
function JobRuns({ job }: { job: Job }) {
  const { t } = useTranslation()
  const runColumns = useMemo<ColumnDef<JobRun, unknown>[]>(() => [
    {
      id: 'scheduled',
      header: t('jobs.scheduledTime'),
      cell: ({ row: { original: run } }) => formatDateTime(run.scheduledTime),
    },
    {
      id: 'outcome',
      header: t('jobs.outcome'),
      cell: ({ row: { original: run } }) => (
        <span className="flex flex-col items-start gap-1">
          <Outcome outcome={run.outcome} />
          <ItemCounts run={run} />
          {run.error && <span className="text-destructive text-xs break-all">{run.error}</span>}
        </span>
      ),
    },
    {
      id: 'by',
      header: t('jobs.by'),
      cell: ({ row: { original: run } }) => (run.requestedBy
        ? <UserName id={run.requestedBy} />
        : <span className="text-muted-foreground">{t('jobs.scheduler')}</span>),
    },
    { id: 'started', header: t('jobs.started'), cell: ({ row }) => formatDateTime(row.original.startedTime) },
    { id: 'finished', header: t('jobs.finished'), cell: ({ row }) => formatDateTime(row.original.finishedTime) },
  ], [t])
  const failingColumns = useMemo<ColumnDef<FailingItem, unknown>[]>(() => [
    { id: 'key', header: t('jobs.itemKey'), cell: ({ row }) => <code className="font-mono text-xs">{row.original.itemKey}</code> },
    { id: 'failures', header: t('jobs.failures'), meta: { className: 'text-right tabular-nums' }, cell: ({ row }) => row.original.failures },
    {
      id: 'error',
      header: t('jobs.error'),
      meta: { className: 'whitespace-normal' },
      cell: ({ row }) => <span className="break-all">{row.original.lastError}</span>,
    },
    { id: 'last', header: t('jobs.lastFailure'), cell: ({ row }) => formatDateTime(row.original.lastFailureTime) },
    { id: 'retry', header: t('jobs.retryAt'), cell: ({ row }) => formatDateTime(row.original.retryAt) },
  ], [t])
  return (
    <div className="flex flex-col gap-6 px-4 pb-6">
      <DataTable
        label={t('jobs.runsTitle', { job: job.name })}
        columns={runColumns}
        getRowId={(run, index) => `${run.scheduledTime}-${run.finishedTime}-${index}`}
        data={job.recentRuns ?? []}
      />
      {job.kind === 'PER_ITEM' && (
        <section className="flex flex-col gap-2">
          <h3 className="text-sm font-semibold">{t('jobs.failing')}</h3>
          {(job.failingItems ?? []).length === 0 ? (
            <p className="text-muted-foreground text-sm">{t('jobs.noFailing')}</p>
          ) : (
            <DataTable
              label={t('jobs.failing')}
              columns={failingColumns}
              getRowId={(item) => item.itemKey ?? ''}
              data={job.failingItems ?? []}
            />
          )}
        </section>
      )}
    </div>
  )
}

/** The time offered first: the latest whose latest run left something undone, else the latest. */
function preferredTime(job: Job): string | undefined {
  const times = job.rerunTimes ?? []
  const undone = times.find((time) => UNDONE.has(latestRunAt(job, time)?.outcome ?? ''))
  return undone ?? times[0]
}

function latestRunAt(job: Job, time: string): JobRun | undefined {
  return (job.recentRuns ?? []).find((run) => run.scheduledTime === time)
}

/** Asks for a past scheduled time of the job to run again, with the reason (POST /api/jobs/{job}/runs). */
function RerunDialog({ job, open, onClose, onStarted }: {
  job: Job
  open: boolean
  onClose: () => void
  onStarted: (pending: Pending) => void
}) {
  const { t } = useTranslation()
  const timeId = useId()
  const reasonId = useId()
  const [time, setTime] = useState<string | undefined>(() => preferredTime(job))
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const times = job.rerunTimes ?? []

  const submit = async () => {
    if (!reason.trim()) {
      setReasonError(t('jobs.reasonRequired'))
      return
    }
    if (!time) return
    setBusy(true)
    try {
      const accepted = await unwrap(api.POST('/api/jobs/{job}/runs', {
        params: { path: { job: job.name ?? '' } },
        body: { scheduledTime: time, reason: reason.trim() },
      }))
      notify.success(t('jobs.rerunStarted', { job: job.name, time: formatDateTime(time) }))
      if (!accepted.replayed) {
        onStarted({ job: job.name ?? '', scheduledTime: time, requestSeqId: accepted.requestSeqId ?? 0,
          since: Date.now() })
      }
      onClose()
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(next) => !next && !busy && onClose()}>
      <DialogContent className="sm:max-w-lg" data-testid="job-rerun-dialog">
        <form
          noValidate
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault()
            void submit()
          }}
        >
          <DialogHeader>
            <DialogTitle>{t('jobs.rerunTitle', { job: job.name })}</DialogTitle>
            <DialogDescription>{t('jobs.rerunHint')}</DialogDescription>
          </DialogHeader>
          {times.length === 0 ? (
            <p className="text-muted-foreground text-sm">{t('jobs.noRerunTimes')}</p>
          ) : (
            <div className="flex flex-col gap-2">
              <Label htmlFor={timeId}>{t('jobs.rerunTime')}</Label>
              <Select value={time} onValueChange={setTime} disabled={busy}>
                <SelectTrigger id={timeId} className="w-full" data-testid="job-rerun-time">
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  {times.map((option) => {
                    const run = latestRunAt(job, option)
                    return (
                      <SelectItem key={option} value={option}>
                        {run
                          ? t('jobs.rerunTimeWith', { time: formatDateTime(option), outcome: t(`jobs.outcomes.${run.outcome}`) })
                          : formatDateTime(option)}
                      </SelectItem>
                    )
                  })}
                </SelectContent>
              </Select>
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={reasonId}>{t('jobs.reason')}</Label>
            <Textarea
              id={reasonId}
              value={reason}
              rows={4}
              maxLength={MAX_REASON}
              required
              disabled={busy}
              aria-invalid={reasonError ? true : undefined}
              aria-describedby={reasonError ? `${reasonId}-error` : undefined}
              onChange={(event) => {
                setReason(event.target.value)
                if (event.target.value.trim()) setReasonError(null)
              }}
              data-testid="job-rerun-reason"
            />
            {reasonError && (
              <span id={`${reasonId}-error`} className="text-destructive text-sm">{reasonError}</span>
            )}
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose} disabled={busy}>
              {t('confirm.cancel', { ns: UI_NAMESPACE })}
            </Button>
            <Button type="submit" disabled={busy || !time} data-testid="job-rerun-submit">
              {busy && <Spinner className="text-current" />}
              {t('jobs.rerunSubmit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
