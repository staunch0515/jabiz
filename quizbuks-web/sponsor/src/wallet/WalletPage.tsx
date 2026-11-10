import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
  Badge,
  Button,
  DataTable,
  DescriptionList,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
  PageState,
  RadioGroup,
  RadioGroupItem,
  notify,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileDown, Plus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { FeeAccount } from './FeeAccount'
import { Invoices } from './Invoices'

import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { TRANSACTIONS_PATH } from '../transactions/api'
import '../transactions/messages'
import {
  available,
  availableCash,
  bankAccountSet,
  bankTopUpPath,
  downloadReceipt,
  exchangeKudos,
  parseKudos,
  pathOfTopUp,
  readBankAccount,
  readCashSettings,
  readTopUpLimit,
  readTopUpStatuses,
  readWallet,
  rememberJustStarted,
  searchTopUps,
  startBankTopUp,
  startTopUp,
  topUpPath,
  WALLET_QUERY,
  type SponsorWallet,
  type TopUpMethod,
  type TopUpRow,
  type TopUpTarget,
  type TopUpStatus,
} from './api'
import './messages'
import { AreaPageHeader } from '../shell/AreaPageHeader'
import { mainAction } from '../shell/mainAction'
import { useShellState } from '../shell/context'
import { AiUsageMetric } from '../ai/AiUsageMetric'
import { useDashboard } from '../analytics/hooks'

/** The labels of the top-up states, from the server's dictionary (the code until it answered). */
export function useTopUpStatusLabel() {
  const statuses = useQuery({ queryKey: ['qb', 'dict', 'topup-status'], queryFn: readTopUpStatuses,
    staleTime: Infinity })
  return (code: string) => statuses.data?.find((item) => item.code === code)?.label ?? code
}

export const STATUS_BADGES: Record<TopUpStatus, 'success' | 'secondary' | 'outline' | 'warning' | 'destructive'> = {
  OPENING: 'outline',
  OPEN: 'warning',
  PAID: 'success',
  EXPIRED: 'secondary',
  FAILED: 'destructive',
  AWAITING: 'warning',
  REPORTED: 'outline',
  REVIEW: 'outline',
  CANCELLED: 'secondary',
}

/** A bank top-up's range (`qb.sponsor.bank-account`), or that bank transfers are not set up yet. */
function BankLimitNote() {
  const { t } = useTranslation(SPONSOR)
  const account = useQuery({ queryKey: [...WALLET_QUERY, 'bank-account'], queryFn: readBankAccount })
  const data = account.data
  if (!data) return null
  if (!bankAccountSet(data)) {
    return (
      <p className="text-muted-foreground text-sm" data-testid="topup-bank-unavailable">
        {t('wallet.bank.unavailable')}
      </p>
    )
  }
  return data.min !== null && data.max !== null ? (
    <p className="text-muted-foreground text-sm" data-testid="topup-limit">
      {t('wallet.dialog.range', { min: formatKudos(data.min), max: formatKudos(data.max) })}
    </p>
  ) : null
}

/**
 * What the sponsor may top up (Q10b): each top-up's range and, for a new sponsor, the limit in all and what remains
 * (`qb.sponsor.topup-limit`). Only said beforehand: the server decides, and words a refusal itself.
 */
function TopUpLimitNote() {
  const { t } = useTranslation(SPONSOR)
  const limit = useQuery({ queryKey: [...WALLET_QUERY, 'limit'], queryFn: readTopUpLimit })
  const data = limit.data
  if (!data) return null
  return (
    <div className="text-muted-foreground flex flex-col gap-1 text-sm" data-testid="topup-limit">
      {data.min !== null && data.max !== null && (
        <p>{t('wallet.dialog.range', { min: formatKudos(data.min), max: formatKudos(data.max) })}</p>
      )}
      {data.limited && data.maxTotal !== null && data.remaining !== null && (
        <p data-testid="topup-new-sponsor">
          {data.until
            ? t('wallet.dialog.newSponsor', { maxTotal: formatKudos(data.maxTotal),
              remaining: formatKudos(data.remaining), until: formatDateTime(data.until) })
            : t('wallet.dialog.newSponsorFirst', { maxTotal: formatKudos(data.maxTotal),
              remaining: formatKudos(data.remaining) })}
        </p>
      )}
    </div>
  )
}

/**
 * How to pay, the amount, then the top-up's page. By card: `QB_TOPUP_START`, and the page goes on to Stripe's
 * checkout once it is open. By bank transfer (Q13a): `QB_TOPUP_BANK_START`, and the page shows the account and the
 * code to put before the payer's name. One idempotency key per method and amount: retrying the same submission (a
 * lost answer) starts one top-up only. The server checks the amount's range and the sponsor (approved, not banned,
 * verified) and words its refusals.
 */
function TopUpDialog({ open, onOpenChange, preferred }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** F-F3: where a top-up goes first when both are offered (cash on the platform fee account's tab). */
  preferred?: TopUpTarget
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [method, setMethod] = useState<TopUpMethod>('STRIPE')
  // Q87a: where it goes, as the switches allow; a choice only when both are offered.
  const settings = useQuery({ queryKey: [...WALLET_QUERY, 'cash-settings'], queryFn: readCashSettings, enabled: open })
  const [chosenTarget, setChosenTarget] = useState<TopUpTarget | null>(null)
  const choosing = (settings.data?.targets.length ?? 0) > 1
  const offered = settings.data?.targets ?? []
  const target = chosenTarget ?? (preferred && offered.includes(preferred) ? preferred : settings.data?.defaultTarget)
    ?? null
  // Without a choice the server's default decides (the switches as it reads them).
  const sentTarget = choosing ? target : null
  // Bank transfers are chosen only once the platform's account is set up (Q18): until then the choice says so.
  const account = useQuery({ queryKey: [...WALLET_QUERY, 'bank-account'], queryFn: readBankAccount, enabled: open })
  const bankReady = account.data !== undefined && bankAccountSet(account.data)
  const [text, setText] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [invalid, setInvalid] = useState(false)
  const start = useMutation({
    mutationFn: async (amount: number) => method === 'BANK'
      ? { method, topUpId: (await startBankTopUp(amount, key, sentTarget)).topUpId }
      : { method, topUpId: (await startTopUp(amount, key, sentTarget)).topUpId },
    onSuccess: (output) => {
      close()
      if (output.method === 'BANK') {
        navigate(bankTopUpPath(output.topUpId))
        return
      }
      rememberJustStarted(output.topUpId)
      navigate(topUpPath(output.topUpId))
    },
  })
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const amount = parseKudos(text)
    setInvalid(amount === null)
    if (amount !== null && !start.isPending) start.mutate(amount)
  }
  /** Every way of closing (Cancel, Escape, the overlay, a started top-up) starts the next opening afresh. */
  function close() {
    setMethod('STRIPE')
    setChosenTarget(null)
    setText('')
    setInvalid(false)
    setKey(crypto.randomUUID())
    start.reset()
    onOpenChange(false)
  }
  const problem = invalid ? t('wallet.dialog.invalid') : fieldProblem(start.error, 'amount')
  const general = generalProblem(start.error, ['amount'])
  return (
    <Dialog open={open} onOpenChange={(next) => (next ? onOpenChange(true) : close())}>
      <DialogContent data-testid="topup-dialog">
        <form noValidate onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('wallet.dialog.title')}</DialogTitle>
            <DialogDescription>
              {method === 'BANK' ? t('wallet.bank.dialogHint') : t('wallet.dialog.hint')}
            </DialogDescription>
          </DialogHeader>
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-2 text-sm font-medium">{t('wallet.bank.method')}</legend>
            <RadioGroup value={method} data-testid="topup-method" onValueChange={(next) => {
              setMethod(next as TopUpMethod)
              // Another method is another submission.
              setKey(crypto.randomUUID())
              start.reset()
            }}>
              {(['STRIPE', 'BANK'] as const).map((value) => (
                <div key={value} className="flex items-start gap-2">
                  <RadioGroupItem id={`${id}-${value}`} value={value} aria-describedby={`${id}-${value}-hint`}
                    disabled={value === 'BANK' && !bankReady} data-testid={`topup-method-${value}`} />
                  <span className="flex flex-col gap-0.5">
                    <Label htmlFor={`${id}-${value}`}>{t(`wallet.bank.methods.${value}`)}</Label>
                    <span id={`${id}-${value}-hint`} className="text-muted-foreground text-xs">
                      {value === 'BANK' && account.data && !bankReady ? t('wallet.bank.unavailable')
                        : t(`wallet.bank.methodHints.${value}`)}
                    </span>
                  </span>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
          {settings.data && choosing && (
            <fieldset className="flex flex-col gap-2" data-testid="topup-target">
              <legend className="mb-2 text-sm font-medium">{t('wallet.target.legend')}</legend>
              <RadioGroup value={target ?? ''} onValueChange={(next) => {
                setChosenTarget(next as TopUpTarget)
                // Another target is another submission.
                setKey(crypto.randomUUID())
                start.reset()
              }}>
                {settings.data.targets.map((value) => (
                  <div key={value} className="flex items-start gap-2">
                    <RadioGroupItem id={`${id}-target-${value}`} value={value}
                      aria-describedby={`${id}-target-${value}-hint`} data-testid={`topup-target-${value}`} />
                    <span className="flex flex-col gap-0.5">
                      <Label htmlFor={`${id}-target-${value}`}>{t(`wallet.target.${value}`)}</Label>
                      <span id={`${id}-target-${value}-hint`} className="text-muted-foreground text-xs">
                        {t(`wallet.target.${value}Hint`)}
                      </span>
                    </span>
                  </div>
                ))}
              </RadioGroup>
            </fieldset>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-amount`}>{t('wallet.dialog.amount')}</Label>
            <Input id={`${id}-amount`} value={text} inputMode="numeric" autoComplete="off" required maxLength={17}
              aria-invalid={problem ? true : undefined}
              aria-describedby={`${id}-amount-hint${problem ? ` ${id}-amount-error` : ''}`}
              data-testid="topup-amount" onChange={(event) => {
                setText(event.target.value)
                setInvalid(false)
                // Another amount is another submission.
                setKey(crypto.randomUUID())
                start.reset()
              }} />
            <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">{t('wallet.dialog.amountHint')}</p>
            {open && (method === 'BANK' ? <BankLimitNote /> : <TopUpLimitNote />)}
            {problem && <p id={`${id}-amount-error`} className="text-destructive text-sm">{problem}</p>}
          </div>
          {general && (
            <Alert variant="destructive" data-testid="topup-problem"><AlertDescription>{general}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={close}>
              {t('wallet.dialog.cancel')}
            </Button>
            <Button type="submit" disabled={start.isPending} aria-busy={start.isPending || undefined}
              data-testid="topup-submit">
              {method === 'BANK' ? t('wallet.bank.submit') : t('wallet.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

const PAGE_SIZE = 20

/**
 * Q87a: the cash account - its balance, and turning it into Kudos or back as the switches allow. Shown once cash can
 * come in (a top-up may go there) or there is some; the server decides every exchange.
 */
function CashAccount({ wallet }: { wallet: SponsorWallet }) {
  const { t } = useTranslation(SPONSOR)
  const settings = useQuery({ queryKey: [...WALLET_QUERY, 'cash-settings'], queryFn: readCashSettings })
  if (!settings.data) return null
  const shown = settings.data.targets.includes('CASH') || wallet.cashBalance > 0 || wallet.entryFeesUnsettled > 0
  if (!shown) return null
  return (
    <section aria-labelledby="wallet-cash" className="flex flex-col gap-3" data-testid="wallet-cash">
      <h2 id="wallet-cash" className="text-lg font-semibold">{t('wallet.cash.title')}</h2>
      <p className="text-muted-foreground text-sm">{t('wallet.cash.intro')}</p>
      <DescriptionList columns={3} items={[
        { key: 'cash', label: t('wallet.cash.balance'),
          value: <span data-testid="wallet-cash-balance">{formatKudos(wallet.cashBalance)}</span> },
        ...(wallet.cashHeld > 0
          ? [{ key: 'cashHeld', label: t('wallet.cash.held'), value: formatKudos(wallet.cashHeld) }] : []),
        // Q88b (D-Q88-11): the entry fees paid for your quizzes, settled into the cash account each day.
        ...(wallet.entryFeesUnsettled > 0
          ? [{ key: 'entryFees', label: t('wallet.cash.entryFees'),
            value: <span data-testid="wallet-entry-fees">{t('wallet.cash.entryFeesValue',
              { amount: formatKudos(wallet.entryFeesUnsettled) })}</span> }] : []),
      ]} />
      <div className="grid gap-4 sm:grid-cols-2">
        {settings.data.buyEnabled && (
          <Exchange toKudos max={availableCash(wallet)} />
        )}
        {settings.data.sellEnabled && (
          <Exchange toKudos={false} max={available(wallet)} />
        )}
      </div>
    </section>
  )
}

/** One direction of the exchange: an amount and a button; the server's problem shown as it says it. */
function Exchange({ toKudos, max }: { toKudos: boolean; max: number }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const exchange = useMutation({
    mutationFn: (amount: number) => exchangeKudos(toKudos, amount, key),
    onSuccess: async () => {
      notify.success(toKudos ? t('wallet.cash.bought') : t('wallet.cash.sold'))
      setText('')
      setKey(crypto.randomUUID())
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
  })
  const name = toKudos ? 'buy' : 'sell'
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const amount = parseKudos(text)
    setInvalid(amount === null)
    if (amount !== null && !exchange.isPending) exchange.mutate(amount)
  }
  const problem = invalid ? t('wallet.cash.invalid') : fieldProblem(exchange.error, 'amount')
  const general = generalProblem(exchange.error, ['amount'])
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-2 rounded-md border p-3"
      data-testid={`cash-${name}`}>
      <Label htmlFor={`${id}-amount`}>{toKudos ? t('wallet.cash.buy') : t('wallet.cash.sell')}</Label>
      <p id={`${id}-hint`} className="text-muted-foreground text-xs">
        {toKudos ? t('wallet.cash.buyHint') : t('wallet.cash.sellHint')} ({formatKudos(max)})
      </p>
      <div className="flex flex-wrap items-start gap-2">
        <Input id={`${id}-amount`} value={text} inputMode="numeric" autoComplete="off" maxLength={17}
          className="w-40" aria-invalid={problem ? true : undefined}
          aria-describedby={`${id}-hint${problem ? ` ${id}-error` : ''}`} data-testid={`cash-${name}-amount`}
          onChange={(event) => {
            setText(event.target.value)
            setInvalid(false)
            setKey(crypto.randomUUID())
            exchange.reset()
          }} />
        <Button type="submit" variant="outline" disabled={exchange.isPending}
          aria-busy={exchange.isPending || undefined} data-testid={`cash-${name}-submit`}>
          {toKudos ? t('wallet.cash.buy') : t('wallet.cash.sell')}
        </Button>
      </div>
      {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
      {general && <p className="text-destructive text-sm" role="alert">{general}</p>}
    </form>
  )
}

/**
 * The receipt of a paid top-up (F-09): issued by the server the first time (in the language the sponsor uses now),
 * then downloaded as kept. The page makes nothing of its own.
 */
function ReceiptButton({ row, onError }: { row: TopUpRow; onError: (error: unknown) => void }) {
  const { t, i18n } = useTranslation(SPONSOR)
  const receipt = useMutation({
    mutationFn: () => downloadReceipt(row.topUpId, i18n.language),
    onMutate: () => onError(null),
    onError,
  })
  return (
    <Button size="sm" variant="outline" disabled={receipt.isPending} aria-busy={receipt.isPending || undefined}
      data-testid="topup-row-receipt" onClick={() => receipt.mutate()}>
      <FileDown aria-hidden />
      {t('wallet.receipt')}
      <span className="sr-only">: {formatDateTime(row.createdAt)}</span>
    </Button>
  )
}

/**
 * The sponsor's wallet (M-04): what is available for rewards, rewards waiting for a top-up, the totals
 * (`qb.sponsor.wallet`) and the top-ups (`qb.sponsor.topups`), with "Top up". Everything is read from the server's
 * records; the page never changes a balance.
 */
export function WalletPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [topping, setTopping] = useState(false)
  const wallet = useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet })

  const data = wallet.data
  return (
    <section className="flex flex-col gap-6" data-testid="wallet">
      <AreaPageHeader title={t('wallet.title')} description={t('wallet.intro')}
        primaryAction={mainAction({ label: t('wallet.topUp'), icon: Plus, testId: 'topup-open',
          onClick: () => setTopping(true) })} />
      {wallet.isPending ? (
        <PageState kind="loading" />
      ) : wallet.isError || !data ? (
        <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void wallet.refetch()}>{tQb('retry')}</Button>} />
      ) : (
        <section aria-labelledby="wallet-summary" className="flex flex-col gap-3">
          <h2 id="wallet-summary" className="text-lg font-semibold">{t('wallet.summary')}</h2>
          <p className="text-3xl font-semibold" data-testid="wallet-available">
            <span className="text-muted-foreground block text-sm font-normal">{t('wallet.available')}</span>
            {formatKudos(available(data))}
          </p>
          {data.pendingCount > 0 && (
            <Alert variant="warning" data-testid="wallet-pending">
              <AlertDescription>
                {t('wallet.pendingAlert', { amount: formatKudos(data.pendingAmount), count: data.pendingCount })}
              </AlertDescription>
            </Alert>
          )}
          <DescriptionList columns={3} data-testid="wallet-figures" items={[
            { key: 'balance', label: t('wallet.balance'), value: formatKudos(data.balance) },
            ...(data.held > 0 ? [{ key: 'held', label: t('wallet.held'), value: formatKudos(data.held) }] : []),
            { key: 'pending', label: t('wallet.pending'),
              value: t('wallet.pendingValue', { amount: formatKudos(data.pendingAmount), count: data.pendingCount }) },
            { key: 'toppedUp', label: t('wallet.toppedUp'),
              value: t('wallet.toppedUpValue', { amount: formatKudos(data.toppedUp), count: data.topUpCount }) },
            { key: 'spent', label: t('wallet.spent'), value: formatKudos(data.spent) },
            { key: 'takenBack', label: t('wallet.takenBack'), value: formatKudos(data.takenBack) },
          ]} />
        </section>
      )}
      <TopUpsSection />
      <TopUpDialog open={topping} onOpenChange={setTopping} />
    </section>
  )
}

/**
 * The top-ups with their receipts (Q9d): on the Kudos wallet's tab, and on the platform fee account's for a Sponsor
 * without Kudos (who tops up to cash there, F-F3).
 */
function TopUpsSection() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const statusLabel = useTopUpStatusLabel()
  const [receiptError, setReceiptError] = useState<unknown>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'createdAt', desc: true }])
  const topUps = useQuery({
    queryKey: [...WALLET_QUERY, 'topups', pagination, sorting],
    queryFn: () => searchTopUps({
      offset: pagination.pageIndex * pagination.pageSize,
      limit: pagination.pageSize,
      sorts: sorting.map((s) => ({ field: s.id, asc: !s.desc })),
    }),
    placeholderData: keepPreviousData,
  })

  const columns = useMemo<ColumnDef<TopUpRow, unknown>[]>(() => [
    // Q80: as a card, the amount is the title, the time under it, the status a badge, the buttons at the foot.
    {
      id: 'createdAt',
      header: t('wallet.created'),
      meta: { card: 'subtitle' },
      cell: ({ row }) => formatDateTime(row.original.createdAt),
    },
    {
      id: 'amount',
      header: t('wallet.amount'),
      meta: { className: 'text-right', card: 'title' },
      cell: ({ row }) => <span data-testid="topup-row-amount">{formatKudos(row.original.amount)}</span>,
    },
    {
      id: 'status',
      header: t('wallet.status'),
      enableSorting: false,
      meta: { card: 'badge' },
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-1">
          <Badge variant={STATUS_BADGES[row.original.status] ?? 'outline'} data-testid="topup-row-status"
            data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.refundedAmount > 0 && (
            <Badge variant="outline">{t('wallet.refunded')}: {formatKudos(row.original.refundedAmount)}</Badge>
          )}
          {row.original.target === 'CASH' && (
            <Badge variant="outline" data-testid="topup-row-cash">{t('wallet.target.CASH')}</Badge>
          )}
          {row.original.method === 'BANK' && (
            <Badge variant="outline" data-testid="topup-row-bank">
              {t('wallet.bank.methods.BANK')}{row.original.transferCode ? ` · ${row.original.transferCode}` : ''}
            </Badge>
          )}
          {row.original.method === 'MANUAL' && (
            <Badge variant="outline" data-testid="topup-row-manual" data-kind={row.original.manualKind ?? ''}>
              {t(`wallet.manual.kinds.${row.original.manualKind ?? 'PAID_OUTSIDE'}`)}
              {row.original.transferCode ? ` · ${row.original.transferCode}` : ''}
            </Badge>
          )}
        </span>
      ),
    },
    {
      id: 'paidAt',
      header: t('wallet.paidAt'),
      cell: ({ row }) => (row.original.paidAt ? formatDateTime(row.original.paidAt) : '—'),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('wallet.view')}</span>,
      enableSorting: false,
      meta: { card: 'actions' },
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          {row.original.status === 'PAID' && row.original.manualKind !== 'GRANT' && (
            <ReceiptButton row={row.original} onError={setReceiptError} />
          )}
          <Button asChild size="sm" variant={row.original.checkoutUrl ? 'default' : 'outline'}>
            <Link to={pathOfTopUp(row.original)} data-testid="topup-row-open">
              {row.original.checkoutUrl ? t('wallet.pay') : t('wallet.view')}
              <span className="sr-only">: {formatDateTime(row.original.createdAt)}</span>
            </Link>
          </Button>
        </span>
      ),
    },
  ], [t, statusLabel])

  return (
    <section aria-labelledby="wallet-topups" className="flex flex-col gap-3" data-testid="wallet-topups-section">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 id="wallet-topups" className="text-lg font-semibold">{t('wallet.topUps')}</h2>
        <Link to={TRANSACTIONS_PATH} className="text-sm underline underline-offset-4" data-testid="wallet-transactions">
          {t('transactions.walletLink')}
        </Link>
      </div>
      <p className="text-muted-foreground text-sm">{t('wallet.receiptHint')}</p>
      {receiptError !== null && (
        <Alert variant="destructive" data-testid="topup-receipt-problem">
          <AlertDescription>{problemText(receiptError)}</AlertDescription>
        </Alert>
      )}
      {topUps.isError
        ? <PageState kind="error" description={tQb('failed')}
          action={<Button variant="outline" onClick={() => void topUps.refetch()}>{tQb('retry')}</Button>} />
        : (
          <DataTable label={t('wallet.topUpsTable')} columns={columns} data={topUps.data?.items ?? []}
            storageKey="qb.sponsor.topups" getRowId={(row) => row.topUpId} loading={topUps.isPending} stale={topUps.isPlaceholderData}
            empty={t('wallet.noTopUps')} pagination={pagination} onPaginationChange={setPagination}
            rowCount={topUps.data?.total} sorting={sorting} onSortingChange={(next) => {
              setSorting(next)
              setPagination((p) => ({ ...p, pageIndex: 0 }))
            }}
            rowProps={(row) => ({ 'data-testid': 'topup-row', 'data-topup': row.topUpId })} />
        )}
    </section>
  )
}

/** The wallet's figures, read once for the money area's tabs (F-F3). */
function useWallet() {
  return useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet })
}

function WalletState({ wallet }: { wallet: ReturnType<typeof useWallet> }) {
  const { t: tQb } = useTranslation(QB)
  return wallet.isPending ? <PageState kind="loading" /> : (
    <PageState kind="error" description={tQb('failed')}
      action={<Button variant="outline" onClick={() => void wallet.refetch()}>{tQb('retry')}</Button>} />
  )
}

/**
 * The money area's "platform fee account" tab (F-F3): what pays the platform's fees, the cash account (Q87a) with its
 * exchange; a top-up to cash where the switches offer it. Shown to every Sponsor, whatever their reward methods (the
 * wallet page was hidden from those without Kudos, and the cash account and the invoices with it).
 */
export function FeeAccountPage() {
  const { t } = useTranslation(SPONSOR)
  const { kudos } = useShellState()
  const wallet = useWallet()
  const settings = useQuery({ queryKey: [...WALLET_QUERY, 'cash-settings'], queryFn: readCashSettings })
  const [topping, setTopping] = useState(false)
  const data = wallet.data
  const cash = settings.data && data && (settings.data.targets.includes('CASH') || data.cashBalance > 0
    || data.entryFeesUnsettled > 0)
  return (
    <section className="flex flex-col gap-6" data-testid="fee-account">
      <AreaPageHeader title={t('shell.account.title')} description={t('shell.account.intro')}
        primaryAction={settings.data?.targets.includes('CASH')
          ? mainAction({ label: t('shell.account.topUp'), icon: Plus, testId: 'fee-account-topup',
            onClick: () => setTopping(true) }) : undefined} />
      {!data ? <WalletState wallet={wallet} />
        : cash ? <CashAccount wallet={data} />
          : settings.data && <PageState kind="empty" description={t('wallet.cash.none')} />}
      {/* F-M1: the account that pays the platform's fees: the granted credit and the cash, by lot. */}
      {data && <FeeAccount wallet={data} />}
      {!kudos && settings.data?.targets.includes('CASH') && <TopUpsSection />}
      <TopUpDialog open={topping} onOpenChange={setTopping} preferred="CASH" />
    </section>
  )
}

/** The money area's "invoices" tab (F-F3): the platform's invoices (Q87), paid from the cash account. */
export function BillsPage() {
  const { t } = useTranslation(SPONSOR)
  const wallet = useWallet()
  return (
    <section className="flex flex-col gap-6" data-testid="bills">
      <AreaPageHeader title={t('shell.bills.title')} description={t('shell.bills.intro')} />
      {wallet.data ? <Invoices wallet={wallet.data} /> : <WalletState wallet={wallet} />}
    </section>
  )
}

/** The money area's "AI credit" tab (F-F3): this month's use of the AI (Q8a); F-M9 adds buying credit here. */
export function AiCreditPage() {
  const { t } = useTranslation(SPONSOR)
  const dashboard = useDashboard()
  return (
    <section className="flex flex-col gap-6" data-testid="ai-credit">
      <AreaPageHeader title={t('shell.ai.title')} description={t('shell.ai.intro')} />
      {dashboard.data
        ? <div className="grid gap-4 sm:grid-cols-2"><AiUsageMetric zone={dashboard.data.zone} /></div>
        : <PageState kind={dashboard.isError ? 'error' : 'loading'} />}
    </section>
  )
}
