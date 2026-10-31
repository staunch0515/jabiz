import { formatDateTime, i18n } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, Input, Label, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery } from '@tanstack/react-query'
import { ChevronRight, Play, Search } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { playProblemText } from '../play/busy'
import { useStartAttempt } from '../play/useStartAttempt'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { readHistory, type HistoryCategory, type HistoryRow } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import { CoverPlaceholder } from '../qb/Illustrations'
import { LazyImage } from '../qb/view/LazyImage'
import { minutesUntil } from './PublicationPage'

/** Rows per page of the history. */
export const HISTORY_PAGE = 20

/** The history's two kinds (U-40) and both. */
type Show = 'all' | 'finished' | 'unfinished'

const CATEGORIES: Record<Show, HistoryCategory[] | undefined> = {
  all: undefined,
  finished: ['PASSED', 'FAILED'],
  unfinished: ['IN_PROGRESS', 'TIMED_OUT'],
}

const BADGES: Record<HistoryCategory, 'success' | 'secondary' | 'outline' | 'warning'> = {
  PASSED: 'success',
  FAILED: 'secondary',
  IN_PROGRESS: 'outline',
  TIMED_OUT: 'warning',
}

/**
 * One attempt (Q66): the publication's cover and name lead back to its start page; the result, or the attempt to
 * continue, is the round button at the row's end. The score and the reward come under the name, then when it started.
 */
function Row({ row, onContinue, starting }: { row: HistoryRow; onContinue: () => void; starting: boolean }) {
  const { t } = useTranslation(APP)
  const finished = row.category === 'PASSED' || row.category === 'FAILED'
  const left = minutesUntil(row.deadline)
  const page = `/p/${row.publicationId}`
  return (
    <li className="flex items-center gap-3 rounded-lg border p-2" data-testid="history-row" data-category={row.category}>
      <Link to={page} aria-label={row.name} data-testid="history-cover"
        className="bg-muted relative aspect-video w-28 shrink-0 self-start overflow-hidden rounded">
        {row.cover
          ? <LazyImage fileId={row.cover} variant="w320" alt="" className="absolute inset-0 size-full" />
          : <CoverPlaceholder seed={row.publicationId} className="absolute inset-0 size-full" />}
      </Link>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <h2 className="line-clamp-2 text-sm leading-snug font-medium">
          <Link to={page} data-testid="history-name" className="underline-offset-2 hover:underline">{row.name}</Link>
        </h2>
        <p className="flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
          <Badge variant={BADGES[row.category]} data-testid="history-category">
            {/* A survey (Q12) is completed or not, never passed. */}
            {row.kind === 'SURVEY' && finished ? t(`history.surveyCategories.${row.category}`)
              : t(`history.categories.${row.category}`)}
          </Badge>
          {finished && row.kind !== 'SURVEY' && (
            <span data-testid="history-score">{t('history.score', { score: row.score ?? 0, full: row.fullScore })}</span>
          )}
          {finished && <RewardLine row={row} />}
          {row.category === 'TIMED_OUT' && <span>{t('history.timedOut')}</span>}
        </p>
        <p className="text-muted-foreground text-xs">{t('history.startedAt', { time: formatDateTime(row.startedAt) })}</p>
      </div>
      {row.category === 'IN_PROGRESS' && left > 0 ? (
        <Button size="icon" className="size-9 shrink-0 rounded-full" disabled={starting} onClick={onContinue}
          aria-label={`${t('detail.continue', { count: left })}: ${row.name}`} data-testid="history-continue">
          <Play aria-hidden className="ml-0.5" />
        </Button>
      ) : (finished || row.category === 'TIMED_OUT') && (
        <Button asChild size="icon" variant="ghost" className="size-9 shrink-0 rounded-full">
          <Link to={`/result/${row.attemptId}`} data-testid="history-open"
            aria-label={`${t('history.seeResult')}: ${row.name}`}>
            <ChevronRight aria-hidden />
          </Link>
        </Button>
      )}
    </li>
  )
}

/**
 * What became of an attempt's reward, however it is paid: paid or waiting for the sponsor, and where the cash, the
 * prize or the digital prize stands (also the wallet's list of a method's rewards, Q58).
 */
export function RewardLine({ row }: { row: HistoryRow }) {
  const { t } = useTranslation(APP)
  return (
    <>
      {row.rewardStatus === 'PAID' && <span>{t('history.paid', { amount: formatKudos(row.reward) })}</span>}
      {row.rewardStatus === 'PENDING' && (
        <span className="text-warning">{t('history.pending', { amount: formatKudos(row.reward) })}</span>
      )}
      {row.rewardStatus === 'CASH' && row.cashStatus && (
        <span data-testid="history-cash" data-status={row.cashStatus}>
          {t(`history.cash.${row.cashStatus}`, { amount: formatKudos(row.reward), code: row.cashCode ?? '' })}
        </span>
      )}
      {row.rewardStatus === 'REDEEM' && (
        <span data-testid="history-redeem" data-status={row.redeemStatus ?? ''}>
          {t(`history.redeem.${row.redeemStatus ?? 'DUE'}`, { amount: formatKudos(row.reward) })}
        </span>
      )}
      {row.rewardStatus === 'DIGITAL' && (
        <span data-testid="history-digital" data-status={row.digitalStatus ?? ''}>
          {t(`history.digital.${row.digitalStatus ?? 'DUE'}`, { amount: formatKudos(row.reward),
            time: row.digitalSendBy ? formatDateTime(row.digitalSendBy) : '' })}
        </span>
      )}
    </>
  )
}

/**
 * The taker's attempts (U-40 – U-42; template `qb.app.history`), newest first: finished (passed or not, score and
 * reward) and unfinished ones; one in progress and in time continues (`QB_ATTEMPT_START` resumes it at the question
 * and answers saved).
 */
export function HistoryPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const [at] = useState(() => new Date().toISOString())
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [show, setShow] = useState<Show>('all')
  const start = useStartAttempt()
  const history = useInfiniteQuery({
    queryKey: ['qb', 'history', at, q, show, i18n.language],
    queryFn: ({ pageParam }) => readHistory({ at, q, categories: CATEGORIES[show], offset: pageParam,
      limit: HISTORY_PAGE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < HISTORY_PAGE ? undefined : last.offset + HISTORY_PAGE),
  })
  const rows = history.data?.pages.flatMap((page) => page.items) ?? []
  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
  }
  return (
    <section className="flex flex-col gap-4" aria-labelledby="history-title" data-testid="history">
      <h1 id="history-title" className="text-xl font-semibold">{t('history.title')}</h1>
      <form role="search" onSubmit={search} className="flex gap-2">
        <Label htmlFor={`${id}-q`} className="sr-only">{t('history.search')}</Label>
        <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('history.search')}
          data-testid="history-search" onChange={(event) => setText(event.target.value)} />
        <Button type="submit" variant="outline" size="icon" aria-label={t('feed.searchButton')}>
          <Search aria-hidden />
        </Button>
      </form>
      <SegmentedControl value={show} onChange={setShow} aria-label={t('history.show')} data-testid="history-show"
        options={[
          { value: 'all', label: t('history.all') },
          { value: 'finished', label: t('history.finished') },
          { value: 'unfinished', label: t('history.unfinished') },
        ]} />
      <p className="text-muted-foreground text-sm empty:hidden" role="status" data-testid="history-continue-busy">
        {start.busy ? t('play.busy') : ''}
      </p>
      {start.isError && (
        <Alert variant="destructive" data-testid="history-continue-error">
          <AlertDescription>{playProblemText(start.error)}</AlertDescription>
        </Alert>
      )}
      {history.isPending ? (
        <PageState kind="loading" />
      ) : history.isError ? (
        <PageState kind="error" description={problemText(history.error)}
          action={<Button variant="outline" onClick={() => void history.refetch()}>{tQb('retry')}</Button>} />
      ) : rows.length === 0 ? (
        <PageState kind="empty" title={t('history.emptyTitle')}
          description={q || show !== 'all' ? t('history.noMatch') : t('history.empty')}
          action={<Button asChild variant="outline"><Link to="/">{t('history.findQuiz')}</Link></Button>}
          data-testid="history-empty" />
      ) : (
        <>
          <ul className="flex flex-col gap-2" data-testid="history-list">
            {rows.map((row) => (
              <Row key={row.attemptId} row={row} starting={start.isPending}
                onContinue={() => start.mutate(row.publicationId)} />
            ))}
          </ul>
          {history.hasNextPage && (
            <Button variant="outline" disabled={history.isFetchingNextPage} onClick={() => void history.fetchNextPage()}>
              {history.isFetchingNextPage ? <Spinner label={t('feed.more')} /> : t('feed.more')}
            </Button>
          )}
        </>
      )}
    </section>
  )
}
