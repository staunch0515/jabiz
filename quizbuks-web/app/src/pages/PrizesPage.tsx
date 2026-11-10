import { formatDateTime } from '@jabiz/client'
import { Badge, Button, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery } from '@tanstack/react-query'
import { Banknote, ChevronRight, Gift, Store } from 'lucide-react'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { AreaHeader } from '../frame/AreaHeader'
import { PRIZES_QUERY, readPrizes, type Prize, type PrizeKind } from '../frame/prizes'
import '../frame/messages'
import { APP } from '../messages'
import { PassButton } from '../pass/PassDialog'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { rewardPath } from '../qb/play'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'

/** Rows per page of the prizes. */
export const PRIZES_PAGE = 20

type Show = 'open' | 'all'

const KIND_ICONS: Record<PrizeKind, typeof Gift> = { REDEEM: Store, CASH: Banknote, DIGITAL: Gift }

/** What became of a prize, in the words the history already uses for each kind and status. */
function statusLine(prize: Prize, t: (key: string, options?: Record<string, unknown>) => string): string {
  const amount = formatKudos(prize.amount)
  if (prize.kind === 'REDEEM') return t(`history.redeem.${prize.status}`, { amount })
  if (prize.kind === 'CASH') return t(`history.cash.${prize.status}`, { amount, code: prize.code ?? '' })
  if (prize.status === 'DUE') return t('prizes.digitalDue', { amount })
  return t(`history.digital.${prize.status}`, { amount, time: '' })
}

function Row({ prize }: { prize: Prize }) {
  const { t } = useTranslation(APP)
  const Icon = KIND_ICONS[prize.kind]
  const collectable = prize.kind === 'REDEEM' && (prize.status === 'DUE' || prize.status === 'CLAIMED')
  return (
    <li className="flex flex-col gap-2 rounded-lg border p-3" data-testid="prize-row" data-kind={prize.kind}
      data-status={prize.status}>
      <div className="flex items-start gap-3">
        <Icon aria-hidden className="text-primary mt-0.5 size-5 shrink-0" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="text-muted-foreground text-xs">{t(`prizes.kinds.${prize.kind}`)}</p>
          <p className="font-medium">{prize.description ?? prize.publicationName}</p>
          <p className="text-muted-foreground truncate text-sm">
            {[prize.publicationName, prize.sponsorCompany].filter(Boolean).join(' · ')}
          </p>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-xs">
            <Badge variant={prize.status === 'OVERDUE' || prize.status === 'DISPUTED' ? 'warning' : 'outline'}
              data-testid="prize-status">{statusLine(prize, t)}</Badge>
            {prize.kind === 'REDEEM' && prize.expiresAt && collectable && (
              <span className="text-muted-foreground">{t('prizes.collectBy', { time: formatDateTime(prize.expiresAt) })}</span>
            )}
          </p>
        </div>
        <Button asChild size="icon" variant="ghost" className="size-9 shrink-0 rounded-full">
          <Link to={rewardPath({ attemptId: prize.attemptId, sourceKind: prize.sourceKind ?? undefined })} data-testid="prize-open" aria-label={`${t('prizes.open')}: ${prize.publicationName}`}>
            <ChevronRight aria-hidden />
          </Link>
        </Button>
      </div>
      {/* At the counter (Q89c): the pass, from here too. Digital prizes and cash are opened and confirmed on the result. */}
      {prize.kind === 'REDEEM' && prize.status === 'DUE' && (
        <PassButton redemptionId={prize.prizeId} collected={false} />
      )}
    </li>
  )
}

/**
 * The prizes the taker won that still need something (F-F4 section 4.1; template `qb.app.prizes`), under "Answered":
 * to collect at a Sponsor's place, owed in cash, digital. "Waiting" are the open ones (the number on the tab); "All"
 * adds the settled. The pass of a prize to collect is shown here; the digital prize's content and the cash
 * reward's confirmation stay on the attempt's result, which each row opens.
 */
export function PrizesPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const [show, setShow] = useState<Show>('open')
  const prizes = useInfiniteQuery({
    queryKey: [...PRIZES_QUERY, 'list', show],
    queryFn: ({ pageParam }) => readPrizes(show === 'open', pageParam, PRIZES_PAGE),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < PRIZES_PAGE ? undefined : last.offset + PRIZES_PAGE),
    // Opened afresh each time: what was collected a moment ago must not show.
    staleTime: 0,
    gcTime: 0,
  })
  const rows = prizes.data?.pages.flatMap((page) => page.items) ?? []
  return (
    <section className="flex flex-col gap-4" aria-labelledby="history-title" data-testid="prizes">
      <AreaHeader area="history" tab="prizes" id="history-title" title={t('frame.areas.history')} />
      <p className="text-muted-foreground text-sm">{t('prizes.intro')}</p>
      <SegmentedControl value={show} onChange={setShow} aria-label={t('prizes.show')} data-testid="prizes-show"
        options={[
          { value: 'open', label: t('prizes.waiting') },
          { value: 'all', label: t('prizes.all') },
        ]} />
      {prizes.isPending ? (
        <PageState kind="loading" />
      ) : prizes.isError ? (
        <PageState kind="error" description={problemText(prizes.error)}
          action={<Button variant="outline" onClick={() => void prizes.refetch()}>{tQb('retry')}</Button>} />
      ) : rows.length === 0 ? (
        <PageState kind="empty" title={t('prizes.emptyTitle')}
          description={show === 'open' ? t('prizes.empty') : t('prizes.emptyAll')}
          action={<Button asChild variant="outline"><Link to="/">{t('prizes.findQuiz')}</Link></Button>}
          data-testid="prizes-empty" />
      ) : (
        <>
          <ul className="flex flex-col gap-2" data-testid="prizes-list">
            {rows.map((prize) => <Row key={`${prize.kind}:${prize.prizeId}`} prize={prize} />)}
          </ul>
          {prizes.hasNextPage && (
            <Button variant="outline" disabled={prizes.isFetchingNextPage} onClick={() => void prizes.fetchNextPage()}>
              {prizes.isFetchingNextPage ? <Spinner label={t('feed.more')} /> : t('feed.more')}
            </Button>
          )}
        </>
      )}
    </section>
  )
}
