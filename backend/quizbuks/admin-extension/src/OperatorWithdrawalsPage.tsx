import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  DataTable,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  EXTENSION_NAMESPACE,
  Input,
  Label,
  notify,
  PageHeader,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import {
  OPERATOR_WITHDRAWALS_KEY,
  payWithdrawal,
  readOperatorWithdrawals,
  rejectWithdrawal,
  WITHDRAWAL_STATUSES,
  withdrawalVariant,
  type OperatorWithdrawal,
} from './account-api'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PERMISSION } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field. */
const SORTS = { requestedAt: 'requestedAt', amount: 'amount', status: 'status' } as const

const right = { className: 'text-right tabular-nums' }
const ALL = 'ALL'

/** Today in Japan, the bank statement's day (`yyyy-MM-dd`). */
function todayInJapan(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo' }).format(new Date())
}

type Action = { kind: 'pay' | 'reject'; withdrawal: OperatorWithdrawal }

/** Recording the bank payment (paid on, reference) for approval, or rejecting with a reason the operator reads. */
function ActionDialog({ action, onClose }: { action: Action; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const pay = action.kind === 'pay'
  const w = action.withdrawal
  const [paidOn, setPaidOn] = useState(todayInJapan())
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const ready = text.trim() !== '' && (!pay || paidOn !== '')
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !ready) return
    setBusy(true)
    setProblem(null)
    try {
      if (pay) await payWithdrawal(w.withdrawalId, paidOn, text.trim())
      else await rejectWithdrawal(w.withdrawalId, text.trim())
      notify.success(t(pay ? 'operatorWithdrawal.recorded' : 'operatorWithdrawal.rejected'))
      void queryClient.invalidateQueries({ queryKey: OPERATOR_WITHDRAWALS_KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid={`operator-withdrawal-${action.kind}-dialog`}>
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(pay ? 'operatorWithdrawal.payTitle' : 'operatorWithdrawal.rejectTitle',
              { name: w.operatorName ?? w.operatorId })}</DialogTitle>
            <DialogDescription>{t(pay ? 'operatorWithdrawal.payHint' : 'operatorWithdrawal.rejectHint')}</DialogDescription>
          </DialogHeader>
          <p className="font-medium tabular-nums">{money(w.amount)}</p>
          {pay && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-on`}>{t('operatorWithdrawal.paidOn')}</Label>
              <Input id={`${id}-on`} type="date" value={paidOn} required className="w-48" data-testid="withdrawal-paid-on"
                onChange={(event) => setPaidOn(event.target.value)} />
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-text`}>{t(pay ? 'operatorWithdrawal.reference' : 'operatorWithdrawal.reason')}</Label>
            {pay
              ? <Input id={`${id}-text`} value={text} maxLength={64} required data-testid="withdrawal-reference"
                onChange={(event) => setText(event.target.value)} />
              : <Textarea id={`${id}-text`} value={text} maxLength={500} required data-testid="withdrawal-reject-reason"
                onChange={(event) => setText(event.target.value)} />}
          </div>
          {problem && <Alert variant="destructive" data-testid="withdrawal-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('operatorWithdrawal.cancel')}</Button>
            <Button type="submit" variant={pay ? 'default' : 'destructive'} disabled={busy || !ready}
              aria-busy={busy || undefined} data-testid="withdrawal-submit">
              {t(pay ? 'operatorWithdrawal.record' : 'operatorWithdrawal.reject')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The operators' withdrawals (Q48e-2, finance): what each operator's lead asked to be paid from its account, to the
 * bank account frozen with the request. With `qb.operator.settlement.pay` finance records the bank payment (another
 * administrator approves it in the approvals; nothing is posted before that) or rejects it with a reason.
 */
export default function OperatorWithdrawalsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const statusLabel = useLabels(DICTIONARY.operatorWithdrawalStatus)
  const typeLabel = useLabels(DICTIONARY.bankAccountType)
  const payer = can(PERMISSION.settlementPay)
  const [search, setSearch] = useSearchParams()
  const status = search.get('status') ?? ALL
  const [action, setAction] = useState<Action | null>(null)
  const { query, table, sortable } = usePaged([...OPERATOR_WITHDRAWALS_KEY, status],
    (offset, limit, sorts) => readOperatorWithdrawals(status === ALL ? null : status, offset, limit, sorts), true, SORTS)
  const columns = useMemo<ColumnDef<OperatorWithdrawal, unknown>[]>(() => [
    { id: 'operator', header: t('operatorWithdrawal.operator'),
      cell: ({ row }) => row.original.operatorName ?? row.original.operatorId },
    {
      id: 'requestedAt',
      header: t('operatorWithdrawal.requestedAt'),
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span>{when(row.original.requestedAt)}</span>
          {row.original.requestedBy && (
            <span className="text-muted-foreground text-sm"><UserName id={row.original.requestedBy} /></span>
          )}
        </span>
      ),
    },
    { id: 'amount', header: t('operatorWithdrawal.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    {
      id: 'status',
      header: t('operatorWithdrawal.status'),
      cell: ({ row }) => (
        <span className="flex flex-col gap-1">
          <Badge variant={withdrawalVariant(row.original.status)} className="w-fit">{statusLabel(row.original.status)}</Badge>
          {row.original.rejectReason && <span className="text-muted-foreground text-sm">{row.original.rejectReason}</span>}
        </span>
      ),
    },
    {
      id: 'bank',
      header: t('operatorWithdrawal.bank'),
      cell: ({ row }) => {
        const w = row.original
        return (
          <span className="flex flex-col text-sm" data-testid="withdrawal-bank">
            <span>{w.bankCode} {w.bankName} / {w.branchCode} {w.branchName}</span>
            <span>{typeLabel(w.accountType)} {w.accountNumber} · {w.accountHolder}</span>
          </span>
        )
      },
    },
    {
      id: 'payment',
      header: t('operatorWithdrawal.payment'),
      cell: ({ row }) => {
        const w = row.original
        if (!w.paidOn) return ''
        return (
          <span className="flex flex-col text-sm">
            <span>{w.paidOn} · {w.bankReference}</span>
            {w.paymentRecordedBy && <span className="text-muted-foreground"><UserName id={w.paymentRecordedBy} /></span>}
          </span>
        )
      },
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('operatorWithdrawal.actions')}</span>,
      cell: ({ row }) => (payer && row.original.status === 'REQUESTED' ? (
        <span className="flex flex-wrap justify-end gap-2">
          <Button size="sm" data-testid="withdrawal-pay" onClick={() => setAction({ kind: 'pay', withdrawal: row.original })}>
            {t('operatorWithdrawal.pay')}<span className="sr-only">: {row.original.operatorName ?? row.original.operatorId}</span>
          </Button>
          <Button size="sm" variant="outline" data-testid="withdrawal-reject"
            onClick={() => setAction({ kind: 'reject', withdrawal: row.original })}>
            {t('operatorWithdrawal.reject')}<span className="sr-only">: {row.original.operatorName ?? row.original.operatorId}</span>
          </Button>
        </span>
      ) : null),
    },
  ], [t, payer, statusLabel, typeLabel])
  return (
    <div className="flex flex-col gap-4" data-testid="operator-withdrawals-page">
      <PageHeader title={t('operatorWithdrawal.title')} description={t('operatorWithdrawal.description')} className="pb-0" />
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-status`}>{t('operatorWithdrawal.status')}</Label>
        <Select value={status} onValueChange={(value) => {
          const next = new URLSearchParams(search)
          if (value === ALL) next.delete('status')
          else next.set('status', value)
          setSearch(next, { replace: true })
        }}>
          <SelectTrigger id={`${id}-status`} className="w-56" data-testid="operator-withdrawals-status"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value={ALL}>{t('operatorWithdrawal.all')}</SelectItem>
            {WITHDRAWAL_STATUSES.map((value) => <SelectItem key={value} value={value}>{statusLabel(value)}</SelectItem>)}
          </SelectContent>
        </Select>
      </div>
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('operatorWithdrawal.title')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.withdrawalId} empty={t('operatorWithdrawal.none')}
            rowProps={(row) => ({ 'data-testid': 'operator-withdrawal-row', 'data-id': row.withdrawalId })} />
        )}
      {action && <ActionDialog action={action} onClose={() => setAction(null)} />}
    </div>
  )
}
