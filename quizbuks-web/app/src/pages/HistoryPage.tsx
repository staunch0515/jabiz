import { formatDateTime, i18n } from '@jabiz/client'
import { AreaHeader } from '../frame/AreaHeader'
import { Alert, AlertDescription, Button, cn, Input, Label, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery } from '@tanstack/react-query'
import { ChevronRight, Gift, Play, Search } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import '../frame/messages'
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
import { SponsorPicture } from '../qb/view/SponsorPicture'
import { PostAgeText } from '../qb/view/FeedCard'
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

/** The reward kinds that leave the taker something to see through (Q22): shown in the row's quote box. */
const PRIZES = new Set(['CASH', 'REDEEM', 'DIGITAL'])

/**
 * One attempt (Q66; Q113d-1, as a post of X's timeline): the sponsor's picture (40 px) beside the sponsor's name and
 * when it started; the publication's name leads back to its start page, its cover beside the text; then the
 * category as a pill (passed in the reward's gold), the score and a reward paid in Kudos, with the attempt to continue
 * or the result at the line's end. A prize (cash, to collect, digital) is a quote box under it, which opens the result
 * where it is handled.
 */
export function HistoryItem({ row, at, onContinue, starting }: { row: HistoryRow; at: string; onContinue: () => void; starting: boolean }) {
  const { t } = useTranslation(APP)
  const finished = row.category === 'PASSED' || row.category === 'FAILED'
  const left = minutesUntil(row.deadline)
  const page = `/p/${row.publicationId}`
  const sponsor = row.sponsorCompany ?? row.sponsorName ?? null
  const prize = finished && row.rewardStatus && PRIZES.has(row.rewardStatus)
  const result = `/result/${row.attemptId}`
  return (
    <li className="border-border flex gap-2 border-b px-4 py-3" data-testid="history-row" data-category={row.category}>
      <SponsorPicture avatar={row.sponsorAvatar} name={sponsor ?? row.name} />
      <div className="flex min-w-0 flex-1 flex-col">
        <p className="flex h-5 min-w-0 items-center gap-1 whitespace-nowrap">
          {sponsor && <span className="truncate font-bold" data-testid="history-sponsor">{sponsor}</span>}
          {sponsor && <span aria-hidden className="text-muted-foreground">·</span>}
          <span className="text-muted-foreground shrink-0">
            <PostAgeText startAt={row.startedAt} at={at} testId="history-age"
              title={t('history.startedAt', { time: formatDateTime(row.startedAt) })} />
          </span>
        </p>
        <div className="mt-0.5 flex gap-3">
          <h2 className="line-clamp-2 min-w-0 flex-1 text-[15px] leading-5 font-normal">
            <Link to={page} data-testid="history-name" className="underline-offset-2 hover:underline">{row.name}</Link>
          </h2>
          <Link to={page} aria-label={row.name} data-testid="history-cover"
            className="bg-muted border-qb-outline relative aspect-video w-16 shrink-0 self-start overflow-hidden rounded-[8px] border">
            {row.cover
              ? <LazyImage fileId={row.cover} variant="w320" alt="" className="absolute inset-0 size-full" />
              : <CoverPlaceholder seed={row.publicationId} className="absolute inset-0 size-full" />}
          </Link>
        </div>
        <div className="mt-2 flex min-h-6 flex-wrap items-center gap-x-2 gap-y-1">
          <span data-testid="history-category"
            className={cn('inline-flex h-6 items-center rounded-full px-2.5 text-[13px] font-bold',
              row.category === 'PASSED' ? 'bg-qb-reward/15 text-qb-reward-foreground' : 'bg-secondary text-secondary-foreground')}>
            {/* A survey (Q12) is completed or not, never passed. */}
            {row.kind === 'SURVEY' && finished ? t(`history.surveyCategories.${row.category}`)
              : t(`history.categories.${row.category}`)}
          </span>
          {finished && row.kind !== 'SURVEY' && (
            <span className="text-muted-foreground" data-testid="history-score">
              {t('history.score', { score: row.score ?? 0, full: row.fullScore })}
            </span>
          )}
          {finished && !prize && <span className="text-muted-foreground"><RewardLine row={row} /></span>}
          {row.category === 'TIMED_OUT' && <span className="text-muted-foreground">{t('history.timedOut')}</span>}
          <span className="flex-1" />
          {row.category === 'IN_PROGRESS' && left > 0 ? (
            <Button size="sm" className="h-8 rounded-full px-3.5 font-bold" disabled={starting} onClick={onContinue}
              aria-label={`${t('detail.continue', { count: left })}: ${row.name}`} data-testid="history-continue">
              <Play aria-hidden />
              {t('history.continueShort')}
            </Button>
          ) : (finished || row.category === 'TIMED_OUT') && (
            <Button asChild size="icon" variant="ghost" className="-my-1.5 -mr-2 size-9 shrink-0 rounded-full">
              <Link to={result} data-testid="history-open" aria-label={`${t('history.seeResult')}: ${row.name}`}>
                <ChevronRight aria-hidden />
              </Link>
            </Button>
          )}
        </div>
        {prize && (
          <Link to={result} data-testid="history-prize"
            className="border-qb-outline hover:bg-accent/50 mt-3 flex items-center gap-3 rounded-[16px] border p-3">
            <span aria-hidden className="bg-qb-reward/15 text-qb-reward-foreground flex size-8 shrink-0 items-center justify-center rounded-full">
              <Gift className="size-[18px]" />
            </span>
            <span className="min-w-0 flex-1 font-bold"><RewardLine row={row} /></span>
          </Link>
        )}
      </div>
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
 * and answers saved). Q113d-1: the area's tabs "All" (with the former switch to the finished ones) and "Not finished"
 * (the former filter, at its own address); the search opens from the header's button.
 */
export function HistoryPage({ tab }: { tab: 'answered' | 'unfinished' }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const [at] = useState(() => new Date().toISOString())
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [searching, setSearching] = useState(false)
  const [finishedOnly, setFinishedOnly] = useState<Show>('all')
  const show: Show = tab === 'unfinished' ? 'unfinished' : finishedOnly
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
  const searchOpen = searching || !!q
  return (
    <section className="flex flex-col gap-4" aria-labelledby="history-title" data-testid="history">
      <AreaHeader area="history" tab={tab} id="history-title" title={t('frame.areas.history')}
        actions={(
          <Button type="button" variant="ghost" size="icon" className="size-9 rounded-full"
            aria-label={t('history.search')} aria-expanded={searchOpen} data-testid="history-search-open"
            onClick={() => setSearching(!searchOpen)}>
            <Search aria-hidden className="size-5" />
          </Button>
        )} />
      {searchOpen && (
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('history.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('history.search')} autoFocus
            className="rounded-full" data-testid="history-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" className="rounded-full" aria-label={t('feed.searchButton')}>
            <Search aria-hidden />
          </Button>
        </form>
      )}
      {tab === 'answered' && (
        <SegmentedControl value={finishedOnly} onChange={setFinishedOnly} aria-label={t('history.show')}
          data-testid="history-show" className="self-start"
          options={[
            { value: 'all', label: t('history.all') },
            { value: 'finished', label: t('history.finished') },
          ]} />
      )}
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
          {/* Q113d-1: rows across the column, a divider between them, as the home's posts. */}
          <ul className="border-border -mx-4 flex flex-col border-t" data-testid="history-list">
            {rows.map((row) => (
              <HistoryItem key={row.attemptId} row={row} at={at} starting={start.isPending}
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
