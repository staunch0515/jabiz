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
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { LoadFailed } from './components'
import { money, useLabels, usePaged } from './hooks'
import { DICTIONARY, PERMISSION } from './ids'
import { downloadStatement, issueStatement, paySettlement, readSettlements, SETTLEMENTS_KEY, type Settlement } from './settlement-api'

const right = { className: 'text-right tabular-nums' }
const ALL = 'ALL'
const STATUSES = ['ISSUED', 'PAYING', 'PAID'] as const

/** Today in Japan, the bank statement's day (`yyyy-MM-dd`). */
function todayInJapan(): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Tokyo' }).format(new Date())
}

function PayDialog({ settlement, onClose }: { settlement: Settlement; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [paidOn, setPaidOn] = useState(todayInJapan())
  const [reference, setReference] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !reference.trim() || !paidOn) return
    setBusy(true)
    setProblem(null)
    try {
      await paySettlement(settlement.settlementId, paidOn, reference.trim())
      notify.success(t('settlement.recorded'))
      void queryClient.invalidateQueries({ queryKey: SETTLEMENTS_KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="pay-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('settlement.payTitle', { name: settlement.operatorName ?? settlement.operatorId,
              month: settlement.month })}</DialogTitle>
            <DialogDescription>{t('settlement.payHint')}</DialogDescription>
          </DialogHeader>
          <p className="font-medium tabular-nums">{money(settlement.payable)}</p>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-on`}>{t('settlement.paidOn')}</Label>
            <Input id={`${id}-on`} type="date" value={paidOn} required className="w-48" data-testid="pay-on"
              onChange={(event) => setPaidOn(event.target.value)} />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-ref`}>{t('settlement.reference')}</Label>
            <Input id={`${id}-ref`} value={reference} maxLength={64} required data-testid="pay-reference"
              onChange={(event) => setReference(event.target.value)} />
          </div>
          {problem && <Alert variant="destructive" data-testid="pay-problem"><AlertDescription>{problem}</AlertDescription></Alert>}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('settlement.cancel')}</Button>
            <Button type="submit" disabled={busy || !reference.trim() || !paidOn} aria-busy={busy || undefined}
              data-testid="pay-submit">{t('settlement.record')}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The operators' monthly settlements (Q48b). Finance (`qb.admin.finance.read`) sees every operator's, downloads the
 * statement as issued and records a payment (`qb.operator.settlement.pay`; another administrator approves it in the
 * approvals); an operator's member sees its own, without the payment's record.
 */
export default function SettlementsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const id = useId()
  const statusLabel = useLabels(DICTIONARY.settlementStatus)
  const queryClient = useQueryClient()
  const finance = can(PERMISSION.financeRead)
  const payer = finance && can(PERMISSION.settlementPay)
  const [search, setSearch] = useSearchParams()
  const status = search.get('status') ?? ALL
  const [paying, setPaying] = useState<Settlement | null>(null)
  const { query, table } = usePaged([...SETTLEMENTS_KEY, finance, status],
    (offset, limit) => readSettlements(finance, status === ALL ? null : status, offset, limit))
  const columns = useMemo<ColumnDef<Settlement, unknown>[]>(() => [
    ...(finance ? [{ id: 'operator', header: t('settlement.operator'),
      cell: ({ row }) => row.original.operatorName ?? row.original.operatorId } as ColumnDef<Settlement, unknown>] : []),
    { id: 'month', header: t('settlement.month'), cell: ({ row }) => row.original.month },
    { id: 'revenue', header: t('settlement.revenue'), meta: right, cell: ({ row }) => money(row.original.revenue) },
    { id: 'share', header: t('settlement.share'), meta: right, cell: ({ row }) => money(row.original.operatorShare) },
    { id: 'payable', header: t('settlement.payable'), meta: right, cell: ({ row }) => money(row.original.payable) },
    { id: 'status', header: t('settlement.status'),
      cell: ({ row }) => <Badge variant="secondary">{statusLabel(row.original.status)}</Badge> },
    { id: 'paidOn', header: t('settlement.paidOn'), cell: ({ row }) => row.original.paidOn ?? '' },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('settlement.statement')}</span>,
      cell: ({ row }) => {
        const s = row.original
        return (
          <span className="flex flex-wrap justify-end gap-2">
            {payer && !s.documentRunId && (
              <Button size="sm" variant="outline" data-testid="settlement-issue"
                onClick={() => void issueStatement(s.settlementId).then(() => {
                  notify.success(t('settlement.issued'))
                  void queryClient.invalidateQueries({ queryKey: SETTLEMENTS_KEY })
                }).catch((e: unknown) => notify.error(e instanceof ApiError ? e.display : String(e)))}>
                {t('settlement.issue')}<span className="sr-only">: {s.month}</span>
              </Button>
            )}
            {finance && s.documentRunId && (
              <Button size="sm" variant="outline" data-testid="settlement-download"
                onClick={() => void downloadStatement(s.documentRunId!, s.documentNo).catch((e: unknown) =>
                  notify.error(e instanceof ApiError ? e.display : String(e)))}>
                {t('settlement.download')}<span className="sr-only">: {s.month}</span>
              </Button>
            )}
            {payer && s.status === 'ISSUED' && Number(s.payable) > 0 && (
              <Button size="sm" data-testid="settlement-pay" onClick={() => setPaying(s)}>
                {t('settlement.pay')}<span className="sr-only">: {s.month}</span>
              </Button>
            )}
          </span>
        )
      },
    },
  ], [t, finance, payer, statusLabel, queryClient])
  return (
    <div className="flex flex-col gap-4" data-testid="settlements-page">
      <PageHeader title={t(finance ? 'settlement.title' : 'settlement.ownTitle')}
        description={t(finance ? 'settlement.description' : 'settlement.ownDescription')} className="pb-0" />
      {finance && (
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-status`}>{t('settlement.status')}</Label>
          <Select value={status} onValueChange={(value) => {
            const next = new URLSearchParams(search)
            if (value === ALL) next.delete('status')
            else next.set('status', value)
            setSearch(next, { replace: true })
          }}>
            <SelectTrigger id={`${id}-status`} className="w-56" data-testid="settlements-status"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('settlement.all')}</SelectItem>
              {STATUSES.map((value) => <SelectItem key={value} value={value}>{statusLabel(value)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      )}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t(finance ? 'settlement.title' : 'settlement.ownTitle')} columns={columns} {...table}
            getRowId={(row) => row.settlementId} empty={t('settlement.none')}
            rowProps={(row) => ({ 'data-testid': 'settlement-row', 'data-id': row.settlementId })} />
        )}
      {paying && <PayDialog settlement={paying} onClose={() => setPaying(null)} />}
    </div>
  )
}
