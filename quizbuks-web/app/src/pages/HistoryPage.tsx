import { formatDateTime, i18n } from '@jabiz/client'
import { Alert, AlertDescription, Badge, Button, Input, Label, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Play, Search } from 'lucide-react'
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

function Row({ row, onContinue, starting }: { row: HistoryRow; onContinue: () => void; starting: boolean }) {
  const { t } = useTranslation(APP)
  const finished = row.category === 'PASSED' || row.category === 'FAILED'
  const left = minutesUntil(row.deadline)
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3" data-testid="history-row" data-category={row.category}>
      <div className="flex items-start gap-2">
        <h2 className="flex-1 font-medium">{row.name}</h2>
        <Badge variant={BADGES[row.category]} data-testid="history-category">
          {/* A survey (Q12) is completed or not, never passed. */}
          {row.kind === 'SURVEY' && finished ? t(`history.surveyCategories.${row.category}`)
            : t(`history.categories.${row.category}`)}
        </Badge>
      </div>
      <p className="text-muted-foreground text-xs">{t('history.startedAt', { time: formatDateTime(row.startedAt) })}</p>
      {finished && (
        <p className="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
          {row.kind !== 'SURVEY' && (
            <span data-testid="history-score">
              {t('history.score', { score: row.score ?? 0, full: row.fullScore })}
            </span>
          )}
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
        </p>
      )}
      {row.category === 'TIMED_OUT' && <p className="text-sm">{t('history.timedOut')}</p>}
      <div className="flex justify-end">
        {row.category === 'IN_PROGRESS' && left > 0 ? (
          <Button size="sm" disabled={starting} onClick={onContinue} data-testid="history-continue">
            <Play aria-hidden />
            {t('detail.continue', { count: left })}
          </Button>
        ) : (
          <Button asChild size="sm" variant="outline">
            <Link to={finished || row.category === 'TIMED_OUT' ? `/result/${row.attemptId}` : `/p/${row.publicationId}`}
              data-testid="history-open">
              {finished ? t('history.seeResult') : t('history.open')}
              <span className="sr-only">: {row.name}</span>
            </Link>
          </Button>
        )}
      </div>
    </li>
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
