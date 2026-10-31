import { formatDateTime } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, ConfirmDialog, PageState, Spinner } from '@jabiz/ui'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ChartNoAxesColumn, ExternalLink, Pause, Play, Store } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { TitleBadge } from '../pages/TitleBadge'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { CoverPlaceholder } from '../qb/Illustrations'
import { LazyImage } from '../qb/view/LazyImage'
import {
  MY_SPONSOR_QUERY,
  pausePublication,
  readFigures,
  readOnPublications,
  readWeek,
  resumePublication,
  useMySponsor,
  type Figures,
  type OnPublication,
} from './api'

/** The sponsor console, the other SPA of the same site (everything that moves Kudos is there). */
export const SPONSOR_CONSOLE = '/sponsor/'

/**
 * A sponsor's own account in the app (Q68, docs/quizbuks/plans/Q68-sponsor-in-app.md): mostly to look at. Today's
 * figures, the publications on with their counts and last seven days, and the one thing a phone may do: pause or
 * resume a publication. Quizzes, publishing, top-ups and rewards are the sponsor console's, linked at the end.
 */
export function MySponsorPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const account = useMySponsor()
  const sponsor = account.data
  const approved = sponsor?.reviewStatus === 'APPROVED'
  const [at] = useState(() => new Date().toISOString())
  const figures = useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'figures', at], queryFn: () => readFigures(at),
    enabled: approved })
  const publications = useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'publications'], queryFn: readOnPublications,
    enabled: approved })
  if (account.isPending) return <PageState kind="loading" />
  if (account.isError) {
    return <PageState kind="error" description={problemText(account.error)}
      action={<Button variant="outline" onClick={() => void account.refetch()}>{tQb('retry')}</Button>} />
  }
  if (!sponsor) return <PageState kind="notFound" titleAs="h1" description={t('notFound')} />
  return (
    <section className="flex flex-col gap-6" aria-labelledby="my-sponsor-title" data-testid="my-sponsor">
      <div className="flex items-center gap-3">
        {sponsor.avatar
          ? <LazyImage fileId={sponsor.avatar} variant="w128" alt="" className="size-12 shrink-0 rounded-full" />
          : <TitleBadge icon={Store} />}
        <div className="min-w-0">
          <h1 id="my-sponsor-title" className="text-xl font-semibold">{t('mySponsor.title')}</h1>
          <p className="text-muted-foreground flex flex-wrap items-center gap-2 text-sm">
            <span className="truncate" data-testid="my-sponsor-company">{sponsor.company ?? sponsor.contactName}</span>
            <Badge variant={approved ? 'success' : 'secondary'} data-testid="my-sponsor-status"
              data-status={sponsor.reviewStatus}>{t(`mySponsor.status.${sponsor.reviewStatus}`)}</Badge>
          </p>
        </div>
      </div>
      {!approved ? (
        <Alert data-testid="my-sponsor-onboarding">
          <AlertDescription>{t('mySponsor.notApproved')}</AlertDescription>
        </Alert>
      ) : (
        <>
          <FiguresView query={figures} />
          <section className="flex flex-col gap-3" aria-labelledby="my-sponsor-publications">
            <h2 id="my-sponsor-publications" className="font-semibold">{t('mySponsor.publications')}</h2>
            {publications.isPending ? <PageState kind="loading" />
              : publications.isError ? <PageState kind="error" description={problemText(publications.error)} />
              : publications.data.length === 0
                ? <p className="text-muted-foreground text-sm" data-testid="my-sponsor-none">{t('mySponsor.noPublications')}</p>
                : (
                  <ul className="flex flex-col gap-3" data-testid="my-sponsor-list">
                    {publications.data.map((p) => <PublicationRow key={p.publicationId} p={p} />)}
                  </ul>
                )}
          </section>
        </>
      )}
      <div className="flex flex-col gap-2 border-t pt-4">
        <p className="text-muted-foreground text-sm">{t('mySponsor.consoleHint')}</p>
        <Button asChild variant="outline" className="self-start">
          <a href={SPONSOR_CONSOLE} data-testid="my-sponsor-console">
            <ExternalLink aria-hidden />
            {t('mySponsor.console')}
          </a>
        </Button>
      </div>
    </section>
  )
}

function FiguresView({ query }: { query: ReturnType<typeof useQuery<Figures>> }) {
  const { t } = useTranslation(APP)
  if (query.isPending) return <PageState kind="loading" />
  if (query.isError) return <PageState kind="error" description={problemText(query.error)} />
  const f = query.data
  const cells: [string, string, string][] = [
    ['live', t('mySponsor.live'), String(f.live)],
    ['answering', t('mySponsor.answering'), String(f.answering)],
    ['spentToday', t('mySponsor.spentToday'), formatKudos(f.spentToday)],
    ['available', t('mySponsor.available'), formatKudos(f.available)],
    ['budgetRemaining', t('mySponsor.budgetRemaining'), formatKudos(f.budgetRemaining)],
    ['paused', t('mySponsor.paused'), String(f.paused)],
  ]
  return (
    <section className="flex flex-col gap-2" aria-labelledby="my-sponsor-figures">
      <h2 id="my-sponsor-figures" className="font-semibold">{t('mySponsor.figures')}</h2>
      {f.fundsShort > 0 && (
        <Alert variant="warning" data-testid="my-sponsor-funds-short">
          <AlertDescription>{t('mySponsor.fundsShort')}: {f.fundsShort}</AlertDescription>
        </Alert>
      )}
      <dl className="grid grid-cols-2 gap-3 rounded-lg border p-3" data-testid="my-sponsor-figures">
        {cells.map(([key, label, value]) => (
          <div key={key} className="flex flex-col" data-testid={`my-sponsor-${key}`}>
            <dt className="text-muted-foreground text-xs">{label}</dt>
            <dd className="text-lg font-semibold tabular-nums">{value}</dd>
          </div>
        ))}
      </dl>
      {f.pendingCount > 0 && (
        <p className="text-sm" data-testid="my-sponsor-pending">
          {t('mySponsor.pending', { count: f.pendingCount, amount: formatKudos(f.pendingAmount) })}
        </p>
      )}
      {f.refreshedAt && (
        <p className="text-muted-foreground text-xs">{t('mySponsor.refreshed', { time: formatDateTime(f.refreshedAt) })}</p>
      )}
    </section>
  )
}

function PublicationRow({ p }: { p: OnPublication }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const queryClient = useQueryClient()
  const paused = p.status === 'PAUSED'
  const byAdmin = paused && p.pausedBy === 'ADMIN'
  const change = useMutation({
    mutationFn: () => (paused ? resumePublication(p.publicationId) : pausePublication(p.publicationId)),
    onSettled: () => queryClient.invalidateQueries({ queryKey: MY_SPONSOR_QUERY }),
  })
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-2" data-testid="my-sponsor-publication"
      data-status={p.status}>
      <div className="flex items-start gap-3">
        <span className="bg-muted relative aspect-video w-24 shrink-0 overflow-hidden rounded">
          {p.cover
            ? <LazyImage fileId={p.cover} variant="w320" alt="" className="absolute inset-0 size-full" />
            : <CoverPlaceholder seed={p.publicationId} className="absolute inset-0 size-full" />}
        </span>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <h3 className="line-clamp-2 text-sm leading-snug font-medium">{p.name}</h3>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
            <Badge variant={p.status === 'LIVE' ? 'success' : p.status === 'FUNDS_SHORT' ? 'warning' : 'secondary'}
              data-testid="my-sponsor-publication-status">{t(`mySponsor.statuses.${p.status}`)}</Badge>
            <span className="text-muted-foreground">{t('mySponsor.until', { time: formatDateTime(p.endAt) })}</span>
          </p>
          <p className="text-muted-foreground flex flex-wrap gap-x-2 text-xs" data-testid="my-sponsor-counts">
            <span>{t('mySponsor.completed', { count: p.completions })}</span>
            <span>{t('mySponsor.passed', { count: p.passes })}</span>
            <span>{t('mySponsor.nowAnswering', { count: p.answering })}</span>
            <span>{p.budgetCap != null
              ? t('mySponsor.spentOf', { amount: formatKudos(p.spent), cap: formatKudos(p.budgetCap) })
              : t('mySponsor.spent', { amount: formatKudos(p.spent) })}</span>
          </p>
        </div>
        {!byAdmin && (
          <ConfirmDialog
            title={t(paused ? 'mySponsor.resumeTitle' : 'mySponsor.pauseTitle', { name: p.name })}
            description={t(paused ? 'mySponsor.resumeText' : 'mySponsor.pauseText')}
            confirmLabel={t(paused ? 'mySponsor.resume' : 'mySponsor.pause')}
            cancelLabel={t('mySponsor.cancel')}
            onConfirm={() => change.mutateAsync()}
            trigger={
              <Button size="icon" variant={paused ? 'default' : 'outline'} className="size-9 shrink-0 rounded-full"
                disabled={change.isPending} data-testid={paused ? 'my-sponsor-resume' : 'my-sponsor-pause'}
                aria-label={`${t(paused ? 'mySponsor.resume' : 'mySponsor.pause')}: ${p.name}`}>
                {change.isPending ? <Spinner /> : paused ? <Play aria-hidden className="ml-0.5" /> : <Pause aria-hidden />}
              </Button>
            } />
        )}
      </div>
      {byAdmin && (
        <p className="text-warning text-xs" data-testid="my-sponsor-admin-paused">
          {t('mySponsor.pausedByAdmin', { brand: tQb('brand') })}
        </p>
      )}
      {change.isError && (
        <Alert variant="destructive" data-testid="my-sponsor-change-error">
          <AlertDescription>{problemText(change.error)}</AlertDescription>
        </Alert>
      )}
      <Week publicationId={p.publicationId} />
    </li>
  )
}

/** The last seven days of one publication, read when opened: a small table, not a chart. */
function Week({ publicationId }: { publicationId: string }) {
  const { t } = useTranslation(APP)
  const [open, setOpen] = useState(false)
  const [now] = useState(() => new Date())
  const week = useQuery({ queryKey: [...MY_SPONSOR_QUERY, 'week', publicationId], enabled: open,
    queryFn: () => readWeek(publicationId, now) })
  return (
    <details className="group text-sm" data-testid="my-sponsor-week"
      onToggle={(event) => setOpen((event.currentTarget as HTMLDetailsElement).open)}>
      <summary className="text-primary flex cursor-pointer list-none items-center gap-1 text-xs [&::-webkit-details-marker]:hidden">
        <ChartNoAxesColumn aria-hidden className="size-3.5" />
        {t('mySponsor.week')}
      </summary>
      {open && (week.isPending ? <PageState kind="loading" /> : week.isError
        ? <PageState kind="error" description={problemText(week.error)} />
        : (
          <table className="mt-2 w-full text-xs tabular-nums" data-testid="my-sponsor-week-table">
            <caption className="sr-only">{t('mySponsor.weekCaption')}</caption>
            <thead className="text-muted-foreground">
              <tr>
                <th scope="col" className="text-left font-normal">{t('mySponsor.day')}</th>
                <th scope="col" className="text-right font-normal">{t('mySponsor.columns.completed')}</th>
                <th scope="col" className="text-right font-normal">{t('mySponsor.columns.passed')}</th>
                <th scope="col" className="text-right font-normal">{t('mySponsor.columns.spent')}</th>
              </tr>
            </thead>
            <tbody>
              {week.data.map((d) => (
                <tr key={d.day}>
                  <th scope="row" className="text-left font-normal">{d.day.slice(5)}</th>
                  <td className="text-right">{d.completions}</td>
                  <td className="text-right">{d.passes}</td>
                  <td className="text-right">{formatKudos(d.spent)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ))}
    </details>
  )
}
