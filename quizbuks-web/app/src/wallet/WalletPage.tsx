import { formatDate, formatDateTime, i18n } from '@jabiz/client'
import { Badge, Button, Input, Label, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ChevronRight, Search } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link } from '../qb/router'
import {
  BANK_PATH,
  BANK_QUERY,
  PAYOUT_PATH,
  PAYOUT_QUERY,
  readBankSettings,
  readLedger,
  readPayoutAccount,
  readWallet,
  transferable,
  WALLET_QUERY,
  type EntryStatus,
  type EntryType,
  type LedgerRow,
} from './api'
import './messages'

/** Rows per page of the transactions. */
export const LEDGER_PAGE = 20

type Show = 'all' | EntryType

const BADGES: Record<EntryStatus, 'success' | 'secondary' | 'outline' | 'warning' | 'destructive'> = {
  COMPLETED: 'success',
  PROCESSING: 'outline',
  FAILED: 'destructive',
  REVERSED: 'warning',
}

function Entry({ row }: { row: LedgerRow }) {
  const { t } = useTranslation(APP)
  const title = row.type === 'REWARD' ? row.name ?? row.quizTitle ?? t('wallet.ledger.reward')
    : t('wallet.ledger.transfer')
  // A transfer leaves the wallet; a failed or reversed one came back (the template's rows say so by status).
  const out = row.type === 'TRANSFER'
  return (
    <li className="flex flex-col gap-1 rounded-lg border p-3" data-testid="ledger-row" data-type={row.type}
      data-status={row.status}>
      <div className="flex items-start gap-2">
        <span className="flex min-w-0 flex-1 flex-col">
          <span className="text-muted-foreground text-xs">
            {row.type === 'REWARD' ? t('wallet.ledger.reward') : t('wallet.ledger.transfers')}
          </span>
          <span className="truncate font-medium">{title}</span>
        </span>
        <span className="text-right font-semibold tabular-nums" data-testid="ledger-amount">
          {out ? '−' : '+'}{formatKudos(row.amount)}
        </span>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <span className="text-muted-foreground text-xs">{formatDateTime(row.occurredAt)}</span>
        <Badge variant={BADGES[row.status] ?? 'outline'} data-testid="ledger-status">
          {t(`wallet.ledger.statuses.${row.status}`)}
        </Badge>
      </div>
      {out && (row.status === 'FAILED' || row.status === 'REVERSED') && (
        <p className="text-muted-foreground text-xs">{t('wallet.ledger.failedHint')}</p>
      )}
    </li>
  )
}

/** The payout account's state in a line, leading to its page. */
function PayoutCard() {
  const { t } = useTranslation(APP)
  const account = useQuery({ queryKey: PAYOUT_QUERY, queryFn: readPayoutAccount })
  if (account.isPending) return <PageState kind="loading" />
  const status = account.data?.status
  const text = status ? t(`wallet.payout.${status}`) : t('wallet.payout.none')
  return (
    <section aria-labelledby="wallet-payout" className="flex flex-col gap-2 rounded-lg border p-3"
      data-testid="wallet-payout" data-status={status ?? 'NONE'}>
      <h2 id="wallet-payout" className="font-semibold">{t('wallet.payout.title')}</h2>
      <p className="text-sm">{account.isError ? problemText(account.error) : text}</p>
      <Button asChild size="sm" variant={status === 'ENABLED' ? 'outline' : 'default'} className="self-start">
        <Link to={PAYOUT_PATH} data-testid="wallet-payout-open">
          {status ? t('wallet.payout.open') : t('wallet.payout.setUp')}
          <ChevronRight aria-hidden />
        </Link>
      </Button>
    </section>
  )
}

/** Withdrawals to a bank account (Q13b), once they are on: how the taker is paid, leading to its page. */
function BankCard() {
  const { t } = useTranslation(APP)
  const settings = useQuery({ queryKey: BANK_QUERY, queryFn: readBankSettings })
  if (!settings.data?.enabled) return null
  const method = settings.data.method
  return (
    <section aria-labelledby="wallet-bank" className="flex flex-col gap-2 rounded-lg border p-3"
      data-testid="wallet-bank" data-method={method}>
      <h2 id="wallet-bank" className="font-semibold">{t('wallet.bankCard.title')}</h2>
      <p className="text-sm">{t(`wallet.bankCard.${method}`)}</p>
      <Button asChild size="sm" variant="outline" className="self-start">
        <Link to={BANK_PATH} data-testid="wallet-bank-open">
          {t('wallet.bankCard.open')}
          <ChevronRight aria-hidden />
        </Link>
      </Button>
    </section>
  )
}

/**
 * The taker's wallet (U-50 – U-52): the Kudos they have, earned in all, waiting for sponsors and on the way out
 * (`qb.app.wallet`), the payout account's state (`qb.app.payout-account`) and the transactions (`qb.app.ledger`),
 * searched by quiz and shown by kind. Everything is the server's record; the page changes nothing.
 */
export function WalletPage() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [show, setShow] = useState<Show>('all')
  const wallet = useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet })
  const ledger = useInfiniteQuery({
    queryKey: [...WALLET_QUERY, 'ledger', q, show, i18n.language],
    queryFn: ({ pageParam }) => readLedger({ q, type: show === 'all' ? undefined : show, offset: pageParam,
      limit: LEDGER_PAGE }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.items.length < LEDGER_PAGE ? undefined : last.offset + LEDGER_PAGE),
  })
  const rows = ledger.data?.pages.flatMap((page) => page.items) ?? []
  const search = (event: FormEvent) => {
    event.preventDefault()
    setQ(text.trim())
  }
  const data = wallet.data
  return (
    <section className="flex flex-col gap-4" aria-labelledby="wallet-title" data-testid="wallet">
      <h1 id="wallet-title" className="text-xl font-semibold">{t('wallet.title')}</h1>
      {wallet.isPending ? (
        <PageState kind="loading" />
      ) : wallet.isError || !data ? (
        <PageState kind="error" description={problemText(wallet.error)}
          action={<Button variant="outline" onClick={() => void wallet.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <div className="flex flex-col gap-3 rounded-lg border p-4" data-testid="wallet-summary">
          <p className="flex flex-col">
            <span className="text-muted-foreground text-sm">{t('wallet.available')}</span>
            <span className="text-3xl font-semibold tabular-nums" data-testid="wallet-available">
              {formatKudos(data.available)}
            </span>
          </p>
          <dl className="grid grid-cols-[1fr_auto] gap-x-2 gap-y-2 text-sm">
            <div className="col-span-2 grid grid-cols-subgrid">
              <dt className="text-muted-foreground">{t('wallet.totalEarned')}</dt>
              <dd className="text-right tabular-nums" data-testid="wallet-earned">{formatKudos(data.totalEarned)}</dd>
            </div>
            <div className="col-span-2 grid grid-cols-subgrid gap-y-0.5">
              <dt className="text-muted-foreground">{t('wallet.pending')}</dt>
              <dd className="text-right tabular-nums" data-testid="wallet-pending">
                {t('wallet.pendingValue', { amount: formatKudos(data.pendingAmount), count: data.pendingCount })}
              </dd>
              {data.pendingCount > 0 && (
                <dd className="text-muted-foreground col-span-2 text-xs">{t('wallet.pendingHint')}</dd>
              )}
            </div>
            {data.held !== null && data.held > 0 && (
              <>
                <div className="col-span-2 grid grid-cols-subgrid">
                  <dt className="text-muted-foreground">{t('wallet.transferable')}</dt>
                  <dd className="text-right tabular-nums" data-testid="wallet-transferable">
                    {formatKudos(transferable(data))}
                  </dd>
                </div>
                <div className="col-span-2 grid grid-cols-subgrid gap-y-0.5">
                  <dt className="text-muted-foreground">{t('wallet.held')}</dt>
                  <dd className="text-right tabular-nums" data-testid="wallet-held">{formatKudos(data.held)}</dd>
                  {data.heldUntil && (
                    <dd className="text-muted-foreground col-span-2 text-xs" data-testid="wallet-held-until">
                      {t('wallet.heldUntil', { date: formatDate(data.heldUntil) })}
                    </dd>
                  )}
                  <dd className="text-muted-foreground col-span-2 text-xs">{t('wallet.heldHint')}</dd>
                  {data.heldOpen > 0 && (
                    <dd className="text-muted-foreground col-span-2 text-xs" data-testid="wallet-held-open">
                      {t('wallet.heldOpen')}
                    </dd>
                  )}
                </div>
              </>
            )}
            {data.inTransfer > 0 && (
              <div className="col-span-2 grid grid-cols-subgrid">
                <dt className="text-muted-foreground">{t('wallet.inTransfer')}</dt>
                <dd className="text-right tabular-nums" data-testid="wallet-in-transfer">{formatKudos(data.inTransfer)}</dd>
              </div>
            )}
          </dl>
        </div>
      )}
      <PayoutCard />
      <BankCard />
      <section aria-labelledby="ledger-title" className="flex flex-col gap-3">
        <h2 id="ledger-title" className="text-lg font-semibold">{t('wallet.ledger.title')}</h2>
        <form role="search" onSubmit={search} className="flex gap-2">
          <Label htmlFor={`${id}-q`} className="sr-only">{t('wallet.ledger.search')}</Label>
          <Input id={`${id}-q`} type="search" value={text} maxLength={100} placeholder={t('wallet.ledger.search')}
            data-testid="ledger-search" onChange={(event) => setText(event.target.value)} />
          <Button type="submit" variant="outline" size="icon" aria-label={t('wallet.ledger.searchButton')}>
            <Search aria-hidden />
          </Button>
        </form>
        <SegmentedControl value={show} onChange={setShow} aria-label={t('wallet.ledger.show')}
          data-testid="ledger-show" options={[
            { value: 'all', label: t('wallet.ledger.all') },
            { value: 'REWARD', label: t('wallet.ledger.rewards') },
            { value: 'TRANSFER', label: t('wallet.ledger.transfers') },
          ]} />
        {ledger.isPending ? (
          <PageState kind="loading" />
        ) : ledger.isError ? (
          <PageState kind="error" description={problemText(ledger.error)}
            action={<Button variant="outline" onClick={() => void ledger.refetch()}>{tQb('retry')}</Button>} />
        ) : rows.length === 0 ? (
          <PageState kind="empty" description={q || show !== 'all' ? t('wallet.ledger.noMatch') : t('wallet.ledger.empty')}
            data-testid="ledger-empty" />
        ) : (
          <>
            <ul className="flex flex-col gap-2" data-testid="ledger-list">
              {rows.map((row) => <Entry key={`${row.type}:${row.entryId}`} row={row} />)}
            </ul>
            {ledger.hasNextPage && (
              <Button variant="outline" disabled={ledger.isFetchingNextPage}
                onClick={() => void ledger.fetchNextPage()}>
                {ledger.isFetchingNextPage ? <Spinner label={t('wallet.ledger.more')} /> : t('wallet.ledger.more')}
              </Button>
            )}
          </>
        )}
      </section>
    </section>
  )
}
