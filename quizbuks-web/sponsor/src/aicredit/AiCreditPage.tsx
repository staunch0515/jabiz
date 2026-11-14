import { formatDateTime } from '@jabiz/client'
import {
  Alert,
  AlertDescription,
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
  type ColumnDef,
  type PaginationState,
} from '@jabiz/ui'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Plus } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useDashboard } from '../analytics/hooks'
import { AiUsageMetric } from '../ai/AiUsageMetric'
import { SPONSOR } from '../messages'
import { formatKudos } from '../qb/kudos'
import { QB } from '../qb/messages'
import { fieldProblem, generalProblem } from '../qb/problems'
import { AreaPageHeader } from '../shell/AreaPageHeader'
import { mainAction } from '../shell/mainAction'
import { availableCash, parseKudos, readFeeSubAccounts, readWallet, WALLET_QUERY } from '../wallet/api'
import {
  AI_CREDIT_QUERY,
  AI_CREDIT_STANDING_QUERY,
  buyAiCredit,
  giveBackAiCredit,
  readAiCreditJobs,
  readAiCreditLots,
  readAiCreditMovements,
  readAiCreditStanding,
  readAiJobKinds,
  readAiJobStatuses,
  type AiCreditJob,
  type AiCreditLot,
  type AiCreditMovement,
  type AiCreditStanding,
} from './api'
import './messages'

const PAGE_SIZE = 10

/** A dictionary's labels from the server (the code until it answered). */
function useLabels(key: string, read: () => Promise<{ code: string; label: string }[]>) {
  const items = useQuery({ queryKey: ['qb', 'dict', key], queryFn: read, staleTime: Infinity })
  return (code: string | null) => (code == null ? '' : items.data?.find((item) => item.code === code)?.label ?? code)
}

/** A number the server could not give is shown as a dash (with the reason in the text beside it). */
const orDash = (value: number | null) => (value === null ? '—' : formatKudos(value))

/**
 * The AI credit's buy dialog (F-M9 b1): the amount, the smallest purchase when one is set, and what the Platform fee
 * account holds (information only: the server pays granted credit first, then cash, and says how it split).
 */
function BuyDialog({ open, onOpenChange, standing }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  standing: AiCreditStanding
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const wallet = useQuery({ queryKey: [...WALLET_QUERY, 'summary'], queryFn: readWallet, enabled: open })
  const [text, setText] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const buy = useMutation({
    mutationFn: (amount: number) => buyAiCredit(amount, key),
    onSuccess: async () => {
      setKey(crypto.randomUUID())
      await queryClient.invalidateQueries({ queryKey: AI_CREDIT_QUERY })
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
  })
  const close = (next: boolean) => {
    if (buy.isPending) return
    if (!next) {
      setText('')
      setInvalid(false)
      buy.reset()
    }
    onOpenChange(next)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const amount = parseKudos(text)
    setInvalid(amount === null)
    if (amount !== null && !buy.isPending) buy.mutate(amount)
  }
  const problem = invalid ? t('aiCredit.buy.invalid') : fieldProblem(buy.error, 'amount')
  const general = generalProblem(buy.error, ['amount'])
  const bought = buy.data
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent data-testid="ai-credit-buy-dialog">
        <DialogHeader>
          <DialogTitle>{t('aiCredit.buy.title')}</DialogTitle>
          <DialogDescription>{t('aiCredit.buy.description')}</DialogDescription>
        </DialogHeader>
        {bought ? (
          <>
            <p role="status" className="text-sm" data-testid="ai-credit-bought" data-credit={bought.credit}
              data-cash={bought.cash}>
              {t('aiCredit.buy.bought', { amount: formatKudos(bought.amount), credit: formatKudos(bought.credit),
                cash: formatKudos(bought.cash), balance: formatKudos(bought.balance) })}
            </p>
            <DialogFooter>
              <Button data-testid="ai-credit-buy-close" onClick={() => close(false)}>{t('aiCredit.buy.close')}</Button>
            </DialogFooter>
          </>
        ) : (
          <form noValidate onSubmit={submit} className="flex flex-col gap-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-amount`}>{t('aiCredit.buy.amount')}</Label>
              <Input id={`${id}-amount`} value={text} inputMode="numeric" autoComplete="off" maxLength={17}
                className="w-48" aria-invalid={problem ? true : undefined}
                aria-describedby={`${id}-hint${problem ? ` ${id}-error` : ''}`} data-testid="ai-credit-buy-amount"
                onChange={(event) => {
                  setText(event.target.value)
                  setInvalid(false)
                  setKey(crypto.randomUUID())
                  buy.reset()
                }} />
              {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
            </div>
            <div id={`${id}-hint`} className="text-muted-foreground flex flex-col gap-1 text-sm">
              {standing.minPurchase !== null && (
                <p data-testid="ai-credit-buy-min">{t('aiCredit.buy.min', { amount: formatKudos(standing.minPurchase) })}</p>
              )}
              {wallet.data && (
                <p data-testid="ai-credit-buy-funds">
                  {t('aiCredit.buy.funds', { credit: formatKudos(Math.max(0, wallet.data.feeCreditBalance)),
                    cash: formatKudos(availableCash(wallet.data)) })}
                </p>
              )}
              <p>{t('aiCredit.buy.order')}</p>
            </div>
            {general && <p className="text-destructive text-sm" role="alert">{general}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={buy.isPending} onClick={() => close(false)}>
                {t('aiCredit.buy.cancel')}
              </Button>
              <Button type="submit" disabled={buy.isPending} aria-busy={buy.isPending || undefined}
                data-testid="ai-credit-buy-submit">
                {t('aiCredit.buy.submit')}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** The give-back dialog: an amount, or "all that may be given back" (the number the server gives). */
function ReturnDialog({ open, onOpenChange, standing }: {
  open: boolean
  onOpenChange: (open: boolean) => void
  standing: AiCreditStanding
}) {
  const { t } = useTranslation(SPONSOR)
  const id = useId()
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [key, setKey] = useState(() => crypto.randomUUID())
  const giveBack = useMutation({
    mutationFn: (amount: number) => giveBackAiCredit(amount, key),
    onSuccess: async () => {
      setKey(crypto.randomUUID())
      await queryClient.invalidateQueries({ queryKey: AI_CREDIT_QUERY })
      await queryClient.invalidateQueries({ queryKey: WALLET_QUERY })
    },
  })
  const returnable = standing.returnable
  const close = (next: boolean) => {
    if (giveBack.isPending) return
    if (!next) {
      setText('')
      setInvalid(false)
      giveBack.reset()
    }
    onOpenChange(next)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const amount = parseKudos(text)
    setInvalid(amount === null)
    if (amount !== null && !giveBack.isPending) giveBack.mutate(amount)
  }
  const problem = invalid ? t('aiCredit.giveBack.invalid') : fieldProblem(giveBack.error, 'amount')
  const general = generalProblem(giveBack.error, ['amount'])
  const done = giveBack.data
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent data-testid="ai-credit-return-dialog">
        <DialogHeader>
          <DialogTitle>{t('aiCredit.giveBack.title')}</DialogTitle>
          <DialogDescription>{t('aiCredit.giveBack.description')}</DialogDescription>
        </DialogHeader>
        {done ? (
          <>
            <p role="status" className="text-sm" data-testid="ai-credit-returned">
              {t('aiCredit.giveBack.done', { amount: formatKudos(done.amount) })}
            </p>
            <DialogFooter>
              <Button data-testid="ai-credit-return-close" onClick={() => close(false)}>{t('aiCredit.giveBack.close')}</Button>
            </DialogFooter>
          </>
        ) : (
          <form noValidate onSubmit={submit} className="flex flex-col gap-3">
            <p id={`${id}-hint`} className="text-muted-foreground text-sm" data-testid="ai-credit-return-hint">
              {returnable === null ? t('aiCredit.giveBack.unknown')
                : returnable === 0 ? t('aiCredit.giveBack.none')
                  : t('aiCredit.giveBack.hint', { amount: formatKudos(returnable) })}
            </p>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-amount`}>{t('aiCredit.giveBack.amount')}</Label>
              <div className="flex flex-wrap items-start gap-2">
                <Input id={`${id}-amount`} value={text} inputMode="numeric" autoComplete="off" maxLength={17}
                  className="w-48" aria-invalid={problem ? true : undefined}
                  aria-describedby={`${id}-hint${problem ? ` ${id}-error` : ''}`} data-testid="ai-credit-return-amount"
                  onChange={(event) => {
                    setText(event.target.value)
                    setInvalid(false)
                    setKey(crypto.randomUUID())
                    giveBack.reset()
                  }} />
                <Button type="button" variant="outline" disabled={!returnable} data-testid="ai-credit-return-all"
                  onClick={() => {
                    setText(String(returnable))
                    setInvalid(false)
                    setKey(crypto.randomUUID())
                    giveBack.reset()
                  }}>
                  {t('aiCredit.giveBack.all')}
                </Button>
              </div>
              {problem && <p id={`${id}-error`} className="text-destructive text-sm">{problem}</p>}
            </div>
            {general && <p className="text-destructive text-sm" role="alert">{general}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" disabled={giveBack.isPending} onClick={() => close(false)}>
                {t('aiCredit.giveBack.cancel')}
              </Button>
              <Button type="submit" disabled={giveBack.isPending} aria-busy={giveBack.isPending || undefined}
                data-testid="ai-credit-return-submit">
                {t('aiCredit.giveBack.submit')}
              </Button>
            </DialogFooter>
          </form>
        )}
      </DialogContent>
    </Dialog>
  )
}

/** The glance: what is available, left, held and used but not charged yet (all from the server). */
function Glance({ standing, onGiveBack }: { standing: AiCreditStanding; onGiveBack: () => void }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <section aria-labelledby="ai-credit-glance" className="flex flex-col gap-3" data-testid="ai-credit-glance">
      <h2 id="ai-credit-glance" className="text-lg font-semibold">{t('aiCredit.glance.title')}</h2>
      {!standing.feeOn && (
        <Alert data-testid="ai-credit-off"><AlertDescription>{t('aiCredit.glance.off')}</AlertDescription></Alert>
      )}
      {standing.low && (
        <Alert variant="warning" data-testid="ai-credit-low">
          <AlertDescription>{t('aiCredit.glance.low')}</AlertDescription>
        </Alert>
      )}
      {standing.feeOn && (standing.available === null || standing.returnable === null || standing.unbilledWithTax === null) && (
        <Alert data-testid="ai-credit-tax-unset"><AlertDescription>{t('aiCredit.glance.taxUnset')}</AlertDescription></Alert>
      )}
      <DescriptionList columns={4} items={[
        { key: 'available', label: t('aiCredit.glance.available'),
          value: <span data-testid="ai-credit-available">{orDash(standing.available)}</span> },
        { key: 'remaining', label: t('aiCredit.glance.remaining'),
          value: <span data-testid="ai-credit-remaining">{formatKudos(standing.remaining)}</span> },
        { key: 'reserved', label: t('aiCredit.glance.reserved'),
          value: <span data-testid="ai-credit-reserved">{formatKudos(standing.reserved)}</span> },
        { key: 'unbilled', label: t('aiCredit.glance.unbilled'),
          value: <span data-testid="ai-credit-unbilled">{orDash(standing.unbilledWithTax)}</span> },
      ]} />
      <p className="text-muted-foreground text-sm">{t('aiCredit.glance.unbilledHint')}</p>
      <div>
        <Button variant="outline" disabled={!standing.returnable} data-testid="ai-credit-return" onClick={onGiveBack}>
          {t('aiCredit.giveBack.open')}
        </Button>
      </div>
    </section>
  )
}

function Lots() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const subAccount = useLabels('fee-sub-account', readFeeSubAccounts)
  const [page, setPage] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const lots = useQuery({ queryKey: [...AI_CREDIT_QUERY, 'lots', page],
    queryFn: () => readAiCreditLots(page.pageIndex * page.pageSize, page.pageSize), placeholderData: keepPreviousData })
  const columns = useMemo<ColumnDef<AiCreditLot, unknown>[]>(() => [
    { id: 'subAccount', header: t('aiCredit.lots.kind'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => subAccount(row.original.subAccount) },
    { id: 'amount', header: t('aiCredit.lots.amount'), enableSorting: false, meta: { card: 'badge', className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.amount) },
    { id: 'expiresAt', header: t('aiCredit.lots.expires'), enableSorting: false,
      cell: ({ row }) => (row.original.expiresAt ? formatDateTime(row.original.expiresAt)
        : row.original.subAccount === 'CASH' ? t('aiCredit.lots.never') : '—') },
    { id: 'voidedAt', header: t('aiCredit.lots.voided'), enableSorting: false,
      cell: ({ row }) => (row.original.voidedAt ? formatDateTime(row.original.voidedAt) : '—') },
  ], [t, subAccount])
  return (
    <section aria-labelledby="ai-credit-lots" className="flex flex-col gap-3">
      <h2 id="ai-credit-lots" className="text-lg font-semibold">{t('aiCredit.lots.title')}</h2>
      {lots.isError ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p> : (
        <DataTable label={t('aiCredit.lots.table')} columns={columns} data={lots.data?.items ?? []}
          storageKey="qb.sponsor.ai-credit-lots" getRowId={(row) => row.lotId} loading={lots.isPending}
          stale={lots.isPlaceholderData} empty={t('aiCredit.lots.empty')} pagination={page} onPaginationChange={setPage}
          rowCount={lots.data?.total}
          rowProps={(row) => ({ 'data-testid': 'ai-credit-lot', 'data-sub-account': row.subAccount })} />
      )}
    </section>
  )
}

function Jobs() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const kind = useLabels('ai-job-kind', readAiJobKinds)
  const status = useLabels('ai-job-status', readAiJobStatuses)
  const [page, setPage] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const jobs = useQuery({ queryKey: [...AI_CREDIT_QUERY, 'jobs', page],
    queryFn: () => readAiCreditJobs(page.pageIndex * page.pageSize, page.pageSize), placeholderData: keepPreviousData })
  const columns = useMemo<ColumnDef<AiCreditJob, unknown>[]>(() => [
    { id: 'requestedAt', header: t('aiCredit.jobs.requested'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => (row.original.requestedAt ? formatDateTime(row.original.requestedAt) : '—') },
    { id: 'kind', header: t('aiCredit.jobs.kind'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => kind(row.original.kind) },
    { id: 'status', header: t('aiCredit.jobs.status'), enableSorting: false,
      cell: ({ row }) => status(row.original.status) },
    { id: 'reserved', header: t('aiCredit.jobs.reserved'), enableSorting: false, meta: { className: 'text-right' },
      // Still running: its reservation; the fee is not known yet.
      cell: ({ row }) => (row.original.finishedAt === null ? orDash(row.original.reservedYen) : '—') },
    { id: 'fee', header: t('aiCredit.jobs.fee'), enableSorting: false, meta: { card: 'badge', className: 'text-right' },
      // Finished: its fee; what was reserved is released.
      cell: ({ row }) => (row.original.finishedAt === null ? '—' : orDash(row.original.feeYen)) },
    { id: 'charge', header: t('aiCredit.jobs.charge'), enableSorting: false,
      cell: ({ row }) => (row.original.finishedAt === null ? '—' : row.original.charged ? t('aiCredit.jobs.charged')
        : row.original.feeYen ? t('aiCredit.jobs.waiting') : t('aiCredit.jobs.noFee')) },
  ], [t, kind, status])
  return (
    <section aria-labelledby="ai-credit-jobs" className="flex flex-col gap-3">
      <h2 id="ai-credit-jobs" className="text-lg font-semibold">{t('aiCredit.jobs.title')}</h2>
      {jobs.isError ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p> : (
        <DataTable label={t('aiCredit.jobs.table')} columns={columns} data={jobs.data?.items ?? []}
          storageKey="qb.sponsor.ai-credit-jobs" getRowId={(row) => row.aiJobId} loading={jobs.isPending}
          stale={jobs.isPlaceholderData} empty={t('aiCredit.jobs.empty')} pagination={page} onPaginationChange={setPage}
          rowCount={jobs.data?.total}
          rowProps={(row) => ({ 'data-testid': 'ai-credit-job', 'data-charged': String(row.charged) })} />
      )}
    </section>
  )
}

function Movements() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const [page, setPage] = useState<PaginationState>({ pageIndex: 0, pageSize: PAGE_SIZE })
  const movements = useQuery({ queryKey: [...AI_CREDIT_QUERY, 'movements', page],
    queryFn: () => readAiCreditMovements(page.pageIndex * page.pageSize, page.pageSize),
    placeholderData: keepPreviousData })
  const columns = useMemo<ColumnDef<AiCreditMovement, unknown>[]>(() => [
    { id: 'movedAt', header: t('aiCredit.movements.date'), enableSorting: false, meta: { card: 'subtitle' },
      cell: ({ row }) => (row.original.movedAt ? formatDateTime(row.original.movedAt) : '—') },
    { id: 'kind', header: t('aiCredit.movements.kind'), enableSorting: false, meta: { card: 'title' },
      cell: ({ row }) => t(`aiCredit.movements.kinds.${row.original.kind}`) },
    { id: 'amount', header: t('aiCredit.movements.amount'), enableSorting: false,
      meta: { card: 'badge', className: 'text-right' }, cell: ({ row }) => formatKudos(row.original.amount) },
    { id: 'credit', header: t('aiCredit.movements.credit'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.credit) },
    { id: 'cash', header: t('aiCredit.movements.cash'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.cash) },
    { id: 'balance', header: t('aiCredit.movements.balance'), enableSorting: false, meta: { className: 'text-right' },
      cell: ({ row }) => formatKudos(row.original.balance) },
  ], [t])
  return (
    <section aria-labelledby="ai-credit-movements" className="flex flex-col gap-3">
      <h2 id="ai-credit-movements" className="text-lg font-semibold">{t('aiCredit.movements.title')}</h2>
      {movements.isError ? <p className="text-destructive text-sm" role="alert">{tQb('failed')}</p> : (
        <DataTable label={t('aiCredit.movements.table')} columns={columns} data={movements.data?.items ?? []}
          storageKey="qb.sponsor.ai-credit-movements" getRowId={(row) => row.movementId}
          loading={movements.isPending} stale={movements.isPlaceholderData} empty={t('aiCredit.movements.empty')}
          pagination={page} onPaginationChange={setPage} rowCount={movements.data?.total}
          rowProps={(row) => ({ 'data-testid': 'ai-credit-movement', 'data-kind': row.kind })} />
      )}
    </section>
  )
}

/**
 * The money area's "AI credit" tab (F-F3, F-M9 b1): this month's use of the AI, what the credit stands at, buying and
 * giving back, and the credit's lots, the AI requests and the movements. The page shows what the server gives and
 * computes no amount of its own.
 */
export function AiCreditPage() {
  const { t } = useTranslation(SPONSOR)
  const { t: tQb } = useTranslation(QB)
  const dashboard = useDashboard()
  const standing = useQuery({ queryKey: AI_CREDIT_STANDING_QUERY, queryFn: readAiCreditStanding })
  const [buying, setBuying] = useState(false)
  const [returning, setReturning] = useState(false)
  const data = standing.data
  return (
    <section className="flex flex-col gap-6" data-testid="ai-credit">
      <AreaPageHeader title={t('shell.ai.title')} description={t('shell.ai.intro')}
        primaryAction={data?.feeOn
          ? mainAction({ label: t('aiCredit.buy.open'), icon: Plus, testId: 'ai-credit-buy',
            onClick: () => setBuying(true) }) : undefined} />
      {data ? <Glance standing={data} onGiveBack={() => setReturning(true)} />
        : <PageState kind={standing.isError ? 'error' : 'loading'} description={standing.isError ? tQb('failed') : undefined}
          action={standing.isError
            ? <Button variant="outline" onClick={() => void standing.refetch()}>{tQb('retry')}</Button> : undefined} />}
      {dashboard.data && <div className="grid gap-4 sm:grid-cols-2"><AiUsageMetric zone={dashboard.data.zone} /></div>}
      {data && (
        <>
          <Lots />
          <Jobs />
          <Movements />
          <BuyDialog open={buying} onOpenChange={setBuying} standing={data} />
          <ReturnDialog open={returning} onOpenChange={setReturning} standing={data} />
        </>
      )}
    </section>
  )
}
