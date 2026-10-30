import { useQueryClient } from '@tanstack/react-query'
import { Eye } from 'lucide-react'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
  Badge,
  Button,
  CopyButton,
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
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { todayInJapan } from './finance-api'
import { StatusFilter } from './finance-components'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { markPaid, markReturned, readBankWithdrawals, revealAccountNumber, type BankWithdrawal } from './withdrawal-api'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { createdAt: 'createdAt', amount: 'amount', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
const QUERY = ['qb-ext', 'finance', 'bank-withdrawals'] as const

/**
 * The account number of one withdrawal in plain text, on request: each click asks the server, which checks
 * `qb.payout.bank.reveal` and records the display. The value lives in this component only.
 */
function AccountNumber({ transferId }: { transferId: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [plain, setPlain] = useState<{ id: string; value: string } | null>(null)
  const [busy, setBusy] = useState(false)
  if (plain && plain.id === transferId) {
    return (
      <span className="inline-flex items-center gap-1">
        <span className="font-mono" data-testid="withdrawal-number">{plain.value}</span>
        <CopyButton value={plain.value} label={t('withdrawal.revealed')} />
      </span>
    )
  }
  const reveal = async () => {
    setBusy(true)
    try {
      setPlain({ id: transferId, value: await revealAccountNumber(transferId) })
    } catch (e) {
      notify.error(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Button variant="link" size="sm" className="h-auto p-0" disabled={busy} onClick={() => void reveal()}
      data-testid="withdrawal-reveal">
      <Eye aria-hidden />
      {t('withdrawal.reveal')}
    </Button>
  )
}

type Action = { kind: 'paid' | 'returned'; withdrawal: BankWithdrawal }

/**
 * Records a withdrawal paid (the statement's day and reference) or returned by the bank (why). The server refuses a
 * withdrawal no longer waiting, one's own, a day in the future or an entry that paid another; its refusals are shown
 * as it words them.
 */
function ActionDialog({ action, onClose }: { action: Action; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [paidOn, setPaidOn] = useState(todayInJapan())
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const paid = action.kind === 'paid'
  const key = paid ? 'paidDialog' : 'returnedDialog'
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !text.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      const transferId = action.withdrawal.transferId
      if (paid) await markPaid({ transferId, paidOn, paidReference: text.trim() })
      else await markReturned({ transferId, reason: text.trim() })
      notify.success(t(`withdrawal.${key}.done`))
      void queryClient.invalidateQueries({ queryKey: QUERY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid={`withdrawal-${action.kind}-dialog`}>
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(`withdrawal.${key}.title`, { amount: money(action.withdrawal.net) })}</DialogTitle>
            <DialogDescription>{t(`withdrawal.${key}.hint`)}</DialogDescription>
          </DialogHeader>
          {paid && (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-day`}>{t('withdrawal.paidDialog.paidOn')}</Label>
              <Input id={`${id}-day`} type="date" className="w-fit" value={paidOn} max={todayInJapan()} required
                data-testid="withdrawal-paid-on" onChange={(event) => setPaidOn(event.target.value)} />
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-text`}>{t(paid ? 'withdrawal.paidDialog.reference' : 'withdrawal.returnedDialog.reason')}</Label>
            {paid
              ? <Input id={`${id}-text`} value={text} required maxLength={64} autoComplete="off"
                data-testid="withdrawal-reference" onChange={(event) => setText(event.target.value)} />
              : <Textarea id={`${id}-text`} value={text} required maxLength={200}
                data-testid="withdrawal-reason" onChange={(event) => setText(event.target.value)} />}
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="withdrawal-problem"><AlertDescription>{problem}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('withdrawal.cancel')}</Button>
            <Button type="submit" variant={paid ? 'default' : 'destructive'} disabled={busy || !text.trim()}
              aria-busy={busy || undefined} data-testid="withdrawal-submit">
              {t(`withdrawal.${key}.submit`)}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The takers' withdrawals to their bank accounts (docs/quizbuks/plans/Q13-bank-transfer.md, Q13b), newest first, by
 * state. One in review opens the transfer's review (another administrator than the payee approves it); an approved
 * one (`SENDING`) is paid by hand: finance reveals the account number (recorded), pays it in the bank and records
 * the statement's entry, or records that the bank returned it. The money moves only on the server.
 */
export default function BankWithdrawalsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.transferStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const [action, setAction] = useState<Action | null>(null)
  const more = useMoreFilters([
    { kind: 'time', name: 'createdAt', label: t('withdrawal.createdAt') },
  ], 'withdrawal-filters')
  const { query, table, sortable } = usePaged([...QUERY, filterStatus, more.filters],
    (offset, limit, sorts) => readBankWithdrawals(filterStatus, offset, limit, sorts, more.filters), true, SORTS)
  const mayPay = can(PERMISSION.withdrawalPay)
  const mayReveal = can(PERMISSION.bankReveal)
  const columns = useMemo<ColumnDef<BankWithdrawal, unknown>[]>(() => [
    { id: 'createdAt', header: t('withdrawal.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    {
      id: 'taker',
      header: t('withdrawal.taker'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.userId)} className={linkClass}><UserName id={row.original.userId} /></Link>
      ),
    },
    { id: 'amount', header: t('withdrawal.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'net', header: t('withdrawal.net'), meta: right,
      cell: ({ row }) => <span data-testid="withdrawal-net">{money(row.original.net)}</span> },
    {
      id: 'account',
      header: t('withdrawal.account'),
      cell: ({ row }) => {
        const w = row.original
        return (
          <span className="flex flex-col gap-1">
            <span>{t('withdrawal.accountValue', { bank: w.bankName, bankCode: w.bankCode, branch: w.branchName,
              branchCode: w.branchCode, type: t(`withdrawal.types.${w.accountType}`, { defaultValue: w.accountType }),
              holder: w.accountHolder })}</span>
            {mayReveal && w.status === 'SENDING' && <AccountNumber transferId={w.transferId} />}
          </span>
        )
      },
    },
    {
      id: 'status',
      header: t('withdrawal.status'),
      cell: ({ row }) => <Badge variant="outline" data-testid="withdrawal-status">{status(row.original.status)}</Badge>,
    },
    {
      id: 'outcome',
      header: t('withdrawal.outcome'),
      cell: ({ row }) => {
        const w = row.original
        if (w.paidOn) return t('withdrawal.paidValue', { date: w.paidOn, reference: w.paidReference ?? '' })
        return w.failureMessage ?? ''
      },
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('withdrawal.review')}</span>,
      cell: ({ row }) => {
        const w = row.original
        return (
          <span className="flex flex-wrap justify-end gap-2">
            {w.status === 'REVIEW' && (
              <Button size="sm" asChild>
                <Link to={PATH.transferReview(w.transferId)} data-testid="withdrawal-review">{t('withdrawal.review')}</Link>
              </Button>
            )}
            {mayPay && w.status === 'SENDING' && (
              <>
                <Button size="sm" onClick={() => setAction({ kind: 'paid', withdrawal: w })}
                  data-testid="withdrawal-paid">
                  {t('withdrawal.paid')}
                </Button>
                <Button size="sm" variant="outline" onClick={() => setAction({ kind: 'returned', withdrawal: w })}
                  data-testid="withdrawal-returned">
                  {t('withdrawal.returned')}
                </Button>
              </>
            )}
          </span>
        )
      },
    },
  ], [t, status, mayPay, mayReveal])

  return (
    <div className="flex flex-col gap-4" data-testid="bank-withdrawals">
      <PageHeader title={t('withdrawal.title')} description={t('withdrawal.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.transferStatus} value={filterStatus} testId="withdrawal-filter"
          onChange={(value) => {
            const next = new URLSearchParams(search)
            if (value) next.set('status', value)
            else next.delete('status')
            setSearch(next, { replace: true })
          }} />
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('withdrawal.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.transferId}
            empty={t('withdrawal.none')}
            rowProps={(row) => ({ 'data-testid': 'withdrawal-row', 'data-id': row.transferId,
              'data-status': row.status })} />
        )}
      {action && <ActionDialog action={action} onClose={() => setAction(null)} />}
    </div>
  )
}
