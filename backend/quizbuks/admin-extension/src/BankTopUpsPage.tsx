import { useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
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
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { readBankTopUps, recordBankTopUp, todayInJapan, type BankTopUp } from './finance-api'
import { parseAmount } from './finance'
import { StatusFilter } from './finance-components'
import { LoadFailed } from './components'
import { money, useLabels, usePaged, when } from './hooks'
import { useMoreFilters } from './moreFilters'
import { BANK_RECORDABLE, BANK_REVIEW, DICTIONARY, PATH, PERMISSION } from './ids'

/** The columns sorted on the server, by the template's `list.sorts` field (Q53a). */
const SORTS = { createdAt: 'createdAt', status: 'status' } as const

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }

/**
 * Records what the bank statement shows for a bank top-up (`QB_TOPUP_BANK_RECORD`): the amount received (the asked-for
 * amount to start with), the day and the statement's reference. Nothing is credited here: the server asks another
 * administrator's approval, and refuses a statement entry recorded before, a day in the future or a top-up no longer
 * waiting. Its refusals are shown as it words them.
 */
function RecordDialog({ topUp, onClose }: { topUp: BankTopUp; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [amount, setAmount] = useState(topUp.requestedAmount)
  const [receivedOn, setReceivedOn] = useState(todayInJapan())
  const [reference, setReference] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const value = parseAmount(amount)
    setInvalid(value === null)
    if (value === null || busy) return
    setBusy(true)
    setProblem(null)
    try {
      await recordBankTopUp({ topUpId: topUp.topUpId, amount: value, receivedOn, bankReference: reference.trim() })
      notify.success(t('bank.dialog.done'))
      void queryClient.invalidateQueries({ queryKey: ['qb-ext', 'finance', 'bank-topups'] })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="bank-record-dialog">
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t('bank.dialog.title', { code: topUp.transferCode ?? '' })}</DialogTitle>
            <DialogDescription>{t('bank.dialog.hint')}</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-amount`}>{t('bank.dialog.amount')}</Label>
            <Input id={`${id}-amount`} value={amount} inputMode="numeric" autoComplete="off" required maxLength={17}
              aria-invalid={invalid ? true : undefined}
              aria-describedby={`${id}-amount-hint${invalid ? ` ${id}-amount-error` : ''}`}
              data-testid="bank-record-amount" onChange={(event) => {
                setAmount(event.target.value)
                setInvalid(false)
              }} />
            <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">{t('bank.dialog.amountHint')}</p>
            {invalid && <p id={`${id}-amount-error`} className="text-destructive text-sm">{t('bank.dialog.invalid')}</p>}
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-day`}>{t('bank.dialog.receivedOn')}</Label>
            <Input id={`${id}-day`} type="date" className="w-fit" value={receivedOn} max={todayInJapan()} required
              data-testid="bank-record-day" onChange={(event) => setReceivedOn(event.target.value)} />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reference`}>{t('bank.dialog.reference')}</Label>
            <Input id={`${id}-reference`} value={reference} required maxLength={64} autoComplete="off"
              aria-describedby={`${id}-reference-hint`} data-testid="bank-record-reference"
              onChange={(event) => setReference(event.target.value)} />
            <p id={`${id}-reference-hint`} className="text-muted-foreground text-sm">{t('bank.dialog.referenceHint')}</p>
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="bank-record-problem"><AlertDescription>{problem}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('bank.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy || !reference.trim()} aria-busy={busy || undefined}
              data-testid="bank-record-submit">
              {t('bank.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

/**
 * The sponsors' top-ups by bank transfer (docs/quizbuks/plans/Q13-bank-transfer.md, Q13a), newest first, by state or
 * transfer code: what was asked for and reported, and what finance recorded. Finance records the money of one
 * waiting for it; one under review opens its page, where another administrator approves it. Credits happen only on
 * the server, after that approval.
 */
export default function BankTopUpsPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const status = useLabels(DICTIONARY.topUpStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const filterCode = /^\d{7}$/.test(search.get('code') ?? '') ? search.get('code') : null
  const [code, setCode] = useState(filterCode ?? '')
  const [recording, setRecording] = useState<BankTopUp | null>(null)
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'time', name: 'createdAt', label: t('bank.createdAt') },
  ], 'bank-filters')
  const { query, table, sortable } = usePaged(['qb-ext', 'finance', 'bank-topups', filterStatus, filterCode, more.filters],
    (offset, limit, sorts) => readBankTopUps(filterStatus, filterCode, offset, limit, sorts, more.filters), true, SORTS)
  const mayRecord = can(PERMISSION.bankRecord)
  const columns = useMemo<ColumnDef<BankTopUp, unknown>[]>(() => [
    { id: 'createdAt', header: t('bank.createdAt'), cell: ({ row }) => when(row.original.createdAt) },
    { id: 'code', header: t('bank.code'),
      cell: ({ row }) => <code className="font-mono" data-testid="bank-row-code">{row.original.transferCode}</code> },
    {
      id: 'sponsor',
      header: t('bank.sponsor'),
      cell: ({ row }) => (
        <Link to={PATH.user(row.original.sponsorId)} className={linkClass}>
          {row.original.company ?? <UserName id={row.original.sponsorId} />}
        </Link>
      ),
    },
    { id: 'requested', header: t('bank.requested'), meta: right,
      cell: ({ row }) => money(row.original.requestedAmount) },
    {
      id: 'payer',
      header: t('bank.payer'),
      cell: ({ row }) => (row.original.payerName
        ? t('bank.payerValue', { name: row.original.payerName, date: row.original.transferDate ?? '' })
        : <span className="text-muted-foreground">{t('bank.notReported')}</span>),
    },
    {
      id: 'status',
      header: t('bank.status'),
      cell: ({ row }) => <Badge variant="outline" data-testid="bank-row-status">{status(row.original.status)}</Badge>,
    },
    {
      id: 'received',
      header: t('bank.received'),
      cell: ({ row }) => (row.original.receivedOn
        ? t('bank.receivedValue', { amount: money(row.original.amount), date: row.original.receivedOn }) : ''),
    },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('bank.open')}</span>,
      cell: ({ row }) => (
        <span className="flex flex-wrap justify-end gap-2">
          {mayRecord && (BANK_RECORDABLE as readonly string[]).includes(row.original.status) && (
            <Button size="sm" onClick={() => setRecording(row.original)} data-testid="bank-row-record">
              {t('bank.record')}
              <span className="sr-only">: {row.original.transferCode}</span>
            </Button>
          )}
          <Button size="sm" variant={row.original.status === BANK_REVIEW ? 'default' : 'outline'} asChild>
            <Link to={PATH.bankTopUp(row.original.topUpId)} data-testid="bank-row-open">
              {row.original.status === BANK_REVIEW ? t('bank.review') : t('bank.open')}
              <span className="sr-only">: {row.original.transferCode}</span>
            </Link>
          </Button>
        </span>
      ),
    },
  ], [t, status, mayRecord])

  return (
    <div className="flex flex-col gap-4" data-testid="bank-topups">
      <PageHeader title={t('bank.title')} description={t('bank.description')} className="pb-0" />
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.topUpStatus} value={filterStatus} testId="bank-status"
          onChange={(value) => set({ status: value })} />
        <form className="flex items-end gap-2" onSubmit={(event) => {
          event.preventDefault()
          set({ code: /^\d{7}$/.test(code.trim()) ? code.trim() : null })
        }}>
          <div className="flex flex-col gap-1">
            <Label htmlFor="bank-code-filter">{t('bank.codeFilter')}</Label>
            <Input id="bank-code-filter" className="w-36" inputMode="numeric" maxLength={7} value={code}
              data-testid="bank-code" onChange={(event) => setCode(event.target.value)} />
          </div>
          <Button type="submit" variant="outline">{t('bank.open')}</Button>
        </form>
        {(filterStatus || filterCode) && (
          <Button variant="ghost" size="sm" onClick={() => {
            setCode('')
            set({ status: null, code: null })
          }}>
            {t('bank.all')}
          </Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('bank.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.topUpId}
            empty={t('bank.none')}
            rowProps={(row) => ({ 'data-testid': 'bank-row', 'data-id': row.topUpId, 'data-status': row.status })} />
        )}
      {recording && <RecordDialog topUp={recording} onClose={() => setRecording(null)} />}
    </div>
  )
}
