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
  PageHeader,
  PageState,
  RadioGroup,
  RadioGroupItem,
  type ColumnDef,
  type PaginationState,
  type SortingState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query'
import { FileDown, Plus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem, problemText } from '../qb/problems'
import { Link, navigate } from '../qb/router'
import { TRANSACTIONS_PATH } from '../transactions/api'
import '../transactions/messages'
import {
  available,
  bankAccountSet,
  bankTopUpPath,
  downloadReceipt,
  parseKudos,
  pathOfTopUp,
  readBankAccount,
  readTopUpLimit,
  readTopUpStatuses,
  readWallet,
  rememberJustStarted,
  searchTopUps,
  startBankTopUp,
  startTopUp,
  topUpPath,
  WALLET_QUERY,
  type TopUpMethod,
  type TopUpRow,
  type TopUpStatus,
} from './api'
import './messages'

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
function TopUpDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const [method, setMethod] = useState<TopUpMethod>('STRIPE')
  const [text, setText] = useState('')
  const [key, setKey] = useState(() => crypto.randomUUID())
  const [invalid, setInvalid] = useState(false)
  const start = useMutation({
    mutationFn: async (amount: number) => method === 'BANK'
      ? { method, topUpId: (await startBankTopUp(amount, key)).topUpId }
      : { method, topUpId: (await startTopUp(amount, key)).topUpId },
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
                    data-testid={`topup-method-${value}`} />
                  <span className="flex flex-col gap-0.5">
                    <Label htmlFor={`${id}-${value}`}>{t(`wallet.bank.methods.${value}`)}</Label>
                    <span id={`${id}-${value}-hint`} className="text-muted-foreground text-xs">
                      {t(`wallet.bank.methodHints.${value}`)}
                    </span>
                  </span>
                </div>
              ))}
            </RadioGroup>
          </fieldset>
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
  const statusLabel = useTopUpStatusLabel()
  const [topping, setTopping] = useState(false)
  const [receiptError, setReceiptError] = useState<unknown>(null)
  const [pagination, setPagination] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const [sorting, setSorting] = useState<SortingState>([{ id: 'createdAt', desc: true }])
  const wallet = useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet })
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
    {
      id: 'createdAt',
      header: t('wallet.created'),
      cell: ({ row }) => formatDateTime(row.original.createdAt),
    },
    {
      id: 'amount',
      header: t('wallet.amount'),
      meta: { className: 'text-right' },
      cell: ({ row }) => <span data-testid="topup-row-amount">{formatKudos(row.original.amount)}</span>,
    },
    {
      id: 'status',
      header: t('wallet.status'),
      enableSorting: false,
      cell: ({ row }) => (
        <span className="flex flex-wrap gap-1">
          <Badge variant={STATUS_BADGES[row.original.status] ?? 'outline'} data-testid="topup-row-status"
            data-status={row.original.status}>
            {statusLabel(row.original.status)}
          </Badge>
          {row.original.refundedAmount > 0 && (
            <Badge variant="outline">{t('wallet.refunded')}: {formatKudos(row.original.refundedAmount)}</Badge>
          )}
          {row.original.method === 'BANK' && (
            <Badge variant="outline" data-testid="topup-row-bank">
              {t('wallet.bank.methods.BANK')}{row.original.transferCode ? ` · ${row.original.transferCode}` : ''}
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
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          {row.original.status === 'PAID' && <ReceiptButton row={row.original} onError={setReceiptError} />}
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

  const data = wallet.data
  return (
    <section className="flex flex-col gap-6" data-testid="wallet">
      <PageHeader title={t('wallet.title')} description={t('wallet.intro')}
        actions={
          <Button onClick={() => setTopping(true)} data-testid="topup-open">
            <Plus aria-hidden />
            {t('wallet.topUp')}
          </Button>
        } />
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
      <section aria-labelledby="wallet-topups" className="flex flex-col gap-3">
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
              getRowId={(row) => row.topUpId} loading={topUps.isPending} stale={topUps.isPlaceholderData}
              empty={t('wallet.noTopUps')} pagination={pagination} onPaginationChange={setPagination}
              rowCount={topUps.data?.total} sorting={sorting} onSortingChange={(next) => {
                setSorting(next)
                setPagination((p) => ({ ...p, pageIndex: 0 }))
              }}
              rowProps={(row) => ({ 'data-testid': 'topup-row', 'data-topup': row.topUpId })} />
          )}
      </section>
      <TopUpDialog open={topping} onOpenChange={setTopping} />
    </section>
  )
}
