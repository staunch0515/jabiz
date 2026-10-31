import { formatDate, formatDateTime, i18n } from '@jabiz/client'
import { TitleBadge } from '../pages/TitleBadge'
import { Badge, Button, Input, Label, PageState, SegmentedControl, Spinner } from '@jabiz/ui'
import { useInfiniteQuery, useQuery } from '@tanstack/react-query'
import { ArrowLeft, Banknote, Coins, CreditCard, Gift, Hourglass, ReceiptText, Search, type LucideIcon } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { problemText } from '../qb/problems'
import { Link, Redirect } from '../qb/router'
import { MenuItem, MenuList } from '../pages/Menu'
import { KudosOverview } from './KudosOverview'
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
  WALLET_PARTS,
  WALLET_PATH,
  WALLET_QUERY,
  walletPartOf,
  walletPath,
  type WalletPart,
  type EntryStatus,
  type EntryType,
  type LedgerRow,
  type TakerWallet,
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

const ICONS: Record<WalletPart, LucideIcon> = { balance: Hourglass, rewards: Gift, history: ReceiptText }

const SUMMARY_QUERY = [...WALLET_QUERY, 'summary'] as const

/** A part's title: the same words on its page and in the menu. */
function partTitle(part: WalletPart, t: (key: string) => string): string {
  switch (part) {
    case 'rewards': return t('kudos.title')
    case 'history': return t('wallet.ledger.title')
    default: return t(`wallet.parts.${part}`)
  }
}

/**
 * My Kudos (U-50 – U-52; Q62, once the wallet): the Kudos the taker has and has earned in all, then a menu of its
 * parts on pages of their own, as the account's are (Q56): what waits, is held or is on the way out
 * (`qb.app.wallet`), the Kudos by reward method (Q58, `qb.app.kudos`), the transactions (`qb.app.ledger`), and the
 * ways of being paid (`qb.app.payout-account`, the bank account). Everything is the server's record; the pages
 * change nothing.
 */
export function WalletPage({ path }: { path: string }) {
  const part = walletPartOf(path)
  if (part === undefined) return <Redirect to={WALLET_PATH} />
  if (part === null) return <WalletMenu />
  return <WalletPartPage part={part} />
}

function SummaryState({ query }: { query: ReturnType<typeof useSummary> }) {
  const { t: tQb } = useTranslation(QB)
  if (query.isPending) return <PageState kind="loading" />
  return (
    <PageState kind="error" description={problemText(query.error)}
      action={<Button variant="outline" onClick={() => void query.refetch()}>{tQb('retry')}</Button>} />
  )
}

function useSummary() {
  return useQuery({ queryKey: SUMMARY_QUERY, queryFn: readWallet })
}

/** The menu: the Kudos at hand and earned in all, the parts, and the ways of being paid. */
function WalletMenu() {
  const { t } = useTranslation(APP)
  const wallet = useSummary()
  const data = wallet.data
  return (
    <section className="flex flex-col gap-6" aria-labelledby="wallet-title" data-testid="wallet">
      <div className="flex items-center gap-3">
        <TitleBadge icon={Coins} />
        <h1 id="wallet-title" className="text-xl font-semibold">{t('wallet.title')}</h1>
      </div>
      {!data ? <SummaryState query={wallet} /> : (
        <div className="grid grid-cols-2 gap-3 rounded-lg border p-4" data-testid="wallet-summary">
          <p className="flex flex-col">
            <span className="text-muted-foreground text-sm">{t('wallet.available')}</span>
            <span className="text-3xl font-semibold tabular-nums" data-testid="wallet-available">
              {formatKudos(data.available)}
            </span>
          </p>
          <p className="flex flex-col">
            <span className="text-muted-foreground text-sm">{t('wallet.totalEarned')}</span>
            <span className="text-xl font-semibold tabular-nums" data-testid="wallet-earned">
              {formatKudos(data.totalEarned)}
            </span>
          </p>
        </div>
      )}
      <MenuList label={t('wallet.menu.details')}>
        {WALLET_PARTS.map((part) => (
          <MenuItem key={part} to={walletPath(part)} icon={ICONS[part]} title={partTitle(part, t)}
            hint={t(`wallet.hints.${part}`)} testId={`wallet-menu-${part}`} />
        ))}
      </MenuList>
      <MenuList label={t('wallet.menu.payouts')}>
        <PayoutItem />
        <BankItem />
      </MenuList>
    </section>
  )
}

/** The payout account's state, leading to its page. */
function PayoutItem() {
  const { t } = useTranslation(APP)
  const account = useQuery({ queryKey: PAYOUT_QUERY, queryFn: readPayoutAccount })
  const status = account.data?.status
  const hint = account.isPending ? undefined : account.isError ? problemText(account.error)
    : status ? t(`wallet.payout.${status}`) : t('wallet.payout.none')
  return (
    <MenuItem to={PAYOUT_PATH} icon={CreditCard} title={t('wallet.payout.title')} hint={hint}
      testId="wallet-payout" status={account.isPending ? undefined : status ?? 'NONE'} />
  )
}

/** Withdrawals to a bank account (Q13b), once they are on: how the taker is paid, leading to its page. */
function BankItem() {
  const { t } = useTranslation(APP)
  const settings = useQuery({ queryKey: BANK_QUERY, queryFn: readBankSettings })
  if (!settings.data?.enabled) return null
  const method = settings.data.method
  return (
    <MenuItem to={BANK_PATH} icon={Banknote} title={t('wallet.bankCard.title')} hint={t(`wallet.bankCard.${method}`)}
      testId="wallet-bank" status={method} />
  )
}

/** A part of My Kudos on its own page, with the way back to the menu. */
function WalletPartPage({ part }: { part: WalletPart }) {
  const { t } = useTranslation(APP)
  return (
    <section className="flex flex-col gap-4" aria-labelledby="wallet-part-title" data-testid={`wallet-${part}-page`}>
      <div className="flex flex-col gap-2">
        <Link to={WALLET_PATH} data-testid="wallet-back"
          className="text-muted-foreground hover:text-foreground inline-flex w-fit items-center gap-1 text-sm">
          <ArrowLeft aria-hidden className="size-4" />
          {t('wallet.back')}
        </Link>
        <h1 id="wallet-part-title" className="text-xl font-semibold">{partTitle(part, t)}</h1>
      </div>
      {part === 'balance' ? <Balance /> : part === 'rewards' ? <KudosOverview /> : <Ledger />}
    </section>
  )
}

/** What the Kudos at hand are made of: what waits for sponsors, what is held and what is on the way out. */
function Balance() {
  const { t } = useTranslation(APP)
  const wallet = useSummary()
  const data: TakerWallet | undefined = wallet.data
  if (!data) return <SummaryState query={wallet} />
  return (
    <dl className="grid grid-cols-[1fr_auto] gap-x-2 gap-y-2 rounded-lg border p-4 text-sm" data-testid="wallet-balance">
      <div className="col-span-2 grid grid-cols-subgrid">
        <dt className="text-muted-foreground">{t('wallet.available')}</dt>
        <dd className="text-right font-semibold tabular-nums" data-testid="wallet-balance-available">
          {formatKudos(data.available)}
        </dd>
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
      <div className="col-span-2 grid grid-cols-subgrid">
        <dt className="text-muted-foreground">{t('wallet.inTransfer')}</dt>
        <dd className="text-right tabular-nums" data-testid="wallet-in-transfer">{formatKudos(data.inTransfer)}</dd>
      </div>
    </dl>
  )
}

/** The transactions, searched by quiz and shown by kind. */
function Ledger() {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const id = useId()
  const [text, setText] = useState('')
  const [q, setQ] = useState('')
  const [show, setShow] = useState<Show>('all')
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
  return (
    <div className="flex flex-col gap-3" data-testid="ledger">
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
    </div>
  )
}
