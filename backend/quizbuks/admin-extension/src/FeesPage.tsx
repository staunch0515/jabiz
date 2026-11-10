import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useMemo, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useSearchParams } from 'react-router'
import {
  Alert,
  AlertDescription,
  ApiError,
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
  Textarea,
  UserName,
  useAuth,
  type ColumnDef,
} from '@jabiz/admin'
import { EmptyState, LoadFailed, StatusBadge } from './components'
import { FeeCreditGrants } from './FeeCreditGrants'
import { FEES_KEY, INVOICE_STATUSES, readFeeCharges, readFeeInvoices, readFeeRequests, recordFeeRequest, REQUEST_REVIEW,
  REQUEST_STATUSES, type FeeCharge, type FeeInvoice, type FeeRequest, type RequestKind } from './fee-api'
import { parseAmount } from './finance'
import { StatusFilter } from './finance-components'
import { money, useLabels, usePaged, when } from './hooks'
import { DICTIONARY, PATH, PERMISSION } from './ids'
import { readApprovedSponsors, SPONSOR_CHOICES } from './manual-api'
import { useMoreFilters } from './moreFilters'

const linkClass = 'text-primary font-medium underline-offset-4 hover:underline'
const right = { className: 'text-right tabular-nums' }
/** The columns sorted on the server, by each template's `list.sorts` field (Q53a). */
const CHARGE_SORTS = { chargedAt: 'chargedAt', total: 'total' } as const
const INVOICE_SORTS = { issuedAt: 'issuedAt', dueOn: 'dueOn', total: 'total', status: 'status' } as const
const REQUEST_SORTS = { requestedAt: 'requestedAt', status: 'status' } as const

type Tab = 'charges' | 'invoices' | 'requests' | 'grants'

/**
 * Records a fee request (`QB_FEE_REQUEST`, Q87): a fee by hand names the sponsor, the amount before tax and the
 * reason; an invoice voided names the invoice and the reason. Nothing is charged or voided here: the server asks
 * another administrator's approval and refuses an invoice already void, an unknown sponsor or one's own account. Its
 * refusals are shown as it words them.
 */
function RequestDialog({ kind, invoiceId, onClose }: { kind: RequestKind; invoiceId?: string; onClose: () => void }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  // A fee by hand and a grant by hand (F-M2) both name a sponsor and an amount.
  const manual = kind !== 'VOID'
  const credit = kind === 'CREDIT'
  const sponsors = useQuery({ queryKey: [...FEES_KEY, 'sponsors'], queryFn: readApprovedSponsors, enabled: manual })
  const capped = (sponsors.data?.length ?? 0) >= SPONSOR_CHOICES
  const [sponsorId, setSponsorId] = useState('')
  const [typedId, setTypedId] = useState('')
  const [invoice, setInvoice] = useState(invoiceId ?? '')
  const [amount, setAmount] = useState('')
  const [validDays, setValidDays] = useState('')
  const [reason, setReason] = useState('')
  const [invalid, setInvalid] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const chosen = sponsorId || typedId.trim()
  const complete = !!reason.trim() && (manual ? !!chosen : !!invoice.trim())
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const value = manual ? parseAmount(amount) : 0
    const days = credit && validDays.trim() ? Number(validDays.trim()) : null
    const bad = (manual && (value === null || value <= 0))
      || (days !== null && (!Number.isInteger(days) || days < 1 || days > 999))
    setInvalid(bad)
    if (bad || busy || !complete) return
    setBusy(true)
    setProblem(null)
    try {
      await recordFeeRequest(credit
        ? { kind: 'CREDIT', sponsorId: chosen, amount: value!, reason: reason.trim(),
          ...(days !== null ? { validDays: days } : {}) }
        : manual
          ? { kind: 'MANUAL', sponsorId: chosen, amount: value!, reason: reason.trim() }
          : { kind: 'VOID', invoiceId: invoice.trim(), reason: reason.trim() })
      notify.success(t('fee.dialog.done'))
      void queryClient.invalidateQueries({ queryKey: FEES_KEY })
      onClose()
    } catch (e) {
      setProblem(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent data-testid="fee-request-dialog" data-kind={kind}>
        <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{t(credit ? 'fee.dialog.creditTitle' : manual ? 'fee.dialog.manualTitle'
              : 'fee.dialog.voidTitle')}</DialogTitle>
            <DialogDescription>{t(credit ? 'fee.dialog.creditHint' : manual ? 'fee.dialog.manualHint'
              : 'fee.dialog.voidHint')}</DialogDescription>
          </DialogHeader>
          {manual ? (
            <>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-sponsor`}>{t('fee.dialog.sponsor')}</Label>
                {sponsors.isError
                  ? <LoadFailed error={sponsors.error} onRetry={() => void sponsors.refetch()} />
                  : sponsors.data && sponsors.data.length === 0
                    ? <p className="text-muted-foreground text-sm" data-testid="fee-no-sponsors">{t('fee.dialog.noSponsors')}</p>
                    : (
                      <Select value={sponsorId} onValueChange={setSponsorId} disabled={!sponsors.data}>
                        <SelectTrigger id={`${id}-sponsor`} aria-describedby={`${id}-sponsor-hint`}
                          data-testid="fee-request-sponsor">
                          <SelectValue placeholder={t('fee.dialog.sponsorPlaceholder')} />
                        </SelectTrigger>
                        <SelectContent>
                          {(sponsors.data ?? []).map((sponsor) => (
                            <SelectItem key={sponsor.userId} value={sponsor.userId}>
                              {sponsor.company ?? sponsor.userId}{sponsor.contactName ? ` · ${sponsor.contactName}` : ''}
                            </SelectItem>
                          ))}
                        </SelectContent>
                      </Select>
                    )}
                <p id={`${id}-sponsor-hint`} className="text-muted-foreground text-sm">{t('fee.dialog.sponsorHint')}</p>
                {capped && (
                  <>
                    <Label htmlFor={`${id}-sponsor-id`}>{t('fee.dialog.sponsorId')}</Label>
                    <Input id={`${id}-sponsor-id`} value={typedId} autoComplete="off" maxLength={128}
                      aria-describedby={`${id}-sponsor-id-hint`} data-testid="fee-request-sponsor-id"
                      onChange={(event) => {
                        setTypedId(event.target.value)
                        if (event.target.value.trim()) setSponsorId('')
                      }} />
                    <p id={`${id}-sponsor-id-hint`} className="text-muted-foreground text-sm">
                      {t('fee.dialog.sponsorIdHint', { count: SPONSOR_CHOICES })}
                    </p>
                  </>
                )}
              </div>
              <div className="flex flex-col gap-2">
                <Label htmlFor={`${id}-amount`}>{t('fee.dialog.amount')}</Label>
                <Input id={`${id}-amount`} value={amount} inputMode="numeric" autoComplete="off" required maxLength={17}
                  aria-invalid={invalid ? true : undefined}
                  aria-describedby={`${id}-amount-hint${invalid ? ` ${id}-amount-error` : ''}`}
                  data-testid="fee-request-amount" onChange={(event) => {
                    setAmount(event.target.value)
                    setInvalid(false)
                  }} />
                <p id={`${id}-amount-hint`} className="text-muted-foreground text-sm">
                  {t(credit ? 'fee.dialog.creditAmountHint' : 'fee.dialog.amountHint')}
                </p>
                {invalid && <p id={`${id}-amount-error`} className="text-destructive text-sm">{t('fee.dialog.invalid')}</p>}
              </div>
              {credit && (
                <div className="flex flex-col gap-2">
                  <Label htmlFor={`${id}-days`}>{t('fee.dialog.validDays')}</Label>
                  <Input id={`${id}-days`} value={validDays} inputMode="numeric" autoComplete="off" maxLength={3}
                    aria-describedby={`${id}-days-hint`} data-testid="fee-request-valid-days"
                    onChange={(event) => {
                      setValidDays(event.target.value)
                      setInvalid(false)
                    }} />
                  <p id={`${id}-days-hint`} className="text-muted-foreground text-sm">{t('fee.dialog.validDaysHint')}</p>
                </div>
              )}
            </>
          ) : (
            <div className="flex flex-col gap-2">
              <Label htmlFor={`${id}-invoice`}>{t('fee.dialog.invoice')}</Label>
              <Input id={`${id}-invoice`} value={invoice} autoComplete="off" required maxLength={36}
                aria-describedby={`${id}-invoice-hint`} data-testid="fee-request-invoice"
                onChange={(event) => setInvoice(event.target.value)} />
              <p id={`${id}-invoice-hint`} className="text-muted-foreground text-sm">{t('fee.dialog.invoiceHint')}</p>
            </div>
          )}
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-reason`}>{t('fee.dialog.reason')}</Label>
            <Textarea id={`${id}-reason`} value={reason} required maxLength={500} aria-describedby={`${id}-reason-hint`}
              data-testid="fee-request-reason" onChange={(event) => setReason(event.target.value)} />
            <p id={`${id}-reason-hint`} className="text-muted-foreground text-sm">{t('fee.dialog.reasonHint')}</p>
          </div>
          {problem && (
            <Alert variant="destructive" data-testid="fee-request-problem"><AlertDescription>{problem}</AlertDescription></Alert>
          )}
          <DialogFooter>
            <Button type="button" variant="outline" onClick={onClose}>{t('fee.dialog.cancel')}</Button>
            <Button type="submit" disabled={busy || !complete} aria-busy={busy || undefined}
              data-testid="fee-request-submit">
              {t('fee.dialog.submit')}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function SponsorCell({ sponsorId, company }: { sponsorId: string; company: string | null }) {
  return (
    <Link to={PATH.user(sponsorId)} className={linkClass} data-testid="fee-sponsor-link">
      {company ?? <UserName id={sponsorId} />}
    </Link>
  )
}

/** The charges (`qb.admin.fee-charges`): every fee the platform charged, with the invoice it went on. */
function Charges() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const kind = useLabels(DICTIONARY.feeKind)
  const mode = useLabels(DICTIONARY.billingMode)
  const invoiceStatus = useLabels(DICTIONARY.feeInvoiceStatus)
  const more = useMoreFilters([
    { kind: 'eq', name: 'sponsorId', label: t('fee.sponsor') },
    { kind: 'eq', name: 'period', label: t('fee.period') },
    { kind: 'dictionary', name: 'kind', label: t('fee.kind'), dictionary: DICTIONARY.feeKind },
    { kind: 'time', name: 'chargedAt', label: t('fee.chargedAt') },
  ], 'fee-charge-filters')
  const { query, table, sortable } = usePaged([...FEES_KEY, 'charges', more.filters],
    (offset, limit, sorts) => readFeeCharges(offset, limit, sorts, more.filters), true, CHARGE_SORTS)
  const columns = useMemo<ColumnDef<FeeCharge, unknown>[]>(() => [
    { id: 'chargedAt', header: t('fee.chargedAt'), cell: ({ row }) => when(row.original.chargedAt) },
    { id: 'sponsor', header: t('fee.sponsor'),
      cell: ({ row }) => <SponsorCell sponsorId={row.original.sponsorId} company={row.original.company} /> },
    { id: 'period', header: t('fee.period'), cell: ({ row }) => row.original.period },
    { id: 'kind', header: t('fee.kind'),
      cell: ({ row }) => <span data-testid="fee-charge-kind">{kind(row.original.kind)}</span> },
    { id: 'billingMode', header: t('fee.billingMode'), cell: ({ row }) => mode(row.original.billingMode) },
    { id: 'amount', header: t('fee.amount'), meta: right, cell: ({ row }) => money(row.original.amount) },
    { id: 'tax', header: t('fee.tax'), meta: right, cell: ({ row }) => money(row.original.tax) },
    { id: 'total', header: t('fee.total'), meta: right,
      cell: ({ row }) => <span data-testid="fee-charge-total">{money(row.original.total)}</span> },
    { id: 'reason', header: t('fee.reason'), cell: ({ row }) => row.original.reason },
    { id: 'invoice', header: t('fee.invoice'),
      cell: ({ row }) => (row.original.invoiceNo
        ? <span className="flex flex-wrap items-center gap-2">
          <code className="font-mono">{row.original.invoiceNo}</code>
          <StatusBadge code={row.original.invoiceStatus} label={invoiceStatus(row.original.invoiceStatus)} />
        </span>
        : '') },
    { id: 'chargedBy', header: t('fee.chargedBy'),
      cell: ({ row }) => (row.original.chargedBy ? <UserName id={row.original.chargedBy} /> : t('fee.system')) },
  ], [t, kind, mode, invoiceStatus])
  return (
    <div className="flex flex-col gap-4">
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('fee.charges.table')} columns={sortable(columns)} {...table} getRowId={(row) => row.chargeId}
            empty={<EmptyState art="money" title={t('fee.charges.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-charge-row', 'data-id': row.chargeId })} />
        )}
    </div>
  )
}

/** The invoices (`qb.admin.fee-invoices`), by state; finance voids one from here (another administrator approves). */
function Invoices({ onVoid }: { onVoid: ((invoiceId: string) => void) | null }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const status = useLabels(DICTIONARY.feeInvoiceStatus)
  const mode = useLabels(DICTIONARY.billingMode)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'eq', name: 'sponsorId', label: t('fee.sponsor') },
    { kind: 'eq', name: 'period', label: t('fee.period') },
    { kind: 'eq', name: 'invoiceNo', label: t('fee.invoiceNo'), upper: true },
    { kind: 'date', name: 'dueOn', label: t('fee.dueOn') },
    { kind: 'time', name: 'issuedAt', label: t('fee.issuedAt') },
  ], 'fee-invoice-filters')
  const { query, table, sortable } = usePaged([...FEES_KEY, 'invoices', filterStatus, more.filters],
    (offset, limit, sorts) => readFeeInvoices(filterStatus, offset, limit, sorts, more.filters), true, INVOICE_SORTS)
  const columns = useMemo<ColumnDef<FeeInvoice, unknown>[]>(() => [
    { id: 'issuedAt', header: t('fee.issuedAt'), cell: ({ row }) => when(row.original.issuedAt) },
    { id: 'invoiceNo', header: t('fee.invoiceNo'),
      cell: ({ row }) => <code className="font-mono" data-testid="fee-invoice-no">{row.original.invoiceNo}</code> },
    { id: 'sponsor', header: t('fee.sponsor'),
      cell: ({ row }) => <SponsorCell sponsorId={row.original.sponsorId} company={row.original.company} /> },
    { id: 'period', header: t('fee.period'), cell: ({ row }) => row.original.period },
    { id: 'billingMode', header: t('fee.billingMode'), cell: ({ row }) => mode(row.original.billingMode) },
    { id: 'total', header: t('fee.total'), meta: right,
      cell: ({ row }) => <span data-testid="fee-invoice-total">{money(row.original.total)}</span> },
    // F-M1: an invoice may be paid in part.
    { id: 'paidAmount', header: t('fee.paidAmount'), meta: right,
      cell: ({ row }) => <span data-testid="fee-invoice-paid">{money(row.original.paidAmount)}</span> },
    { id: 'dueOn', header: t('fee.dueOn'), cell: ({ row }) => row.original.dueOn ?? '' },
    { id: 'status', header: t('fee.status'),
      cell: ({ row }) => (
        <span className="flex flex-wrap items-center gap-2">
          <StatusBadge code={row.original.status} label={status(row.original.status)} testId="fee-invoice-status" />
          {row.original.remindersSent > 0 && (
            <span className="text-muted-foreground text-sm">{t('fee.reminders')}: {row.original.remindersSent}</span>
          )}
          {row.original.voidedAt && (
            <span className="text-muted-foreground text-sm">
              {t('fee.voided', { at: when(row.original.voidedAt), reason: row.original.voidReason ?? '' })}
            </span>
          )}
        </span>
      ) },
    { id: 'paidAt', header: t('fee.paidAt'), cell: ({ row }) => when(row.original.paidAt) },
    ...(onVoid ? [{
      id: 'actions',
      header: () => <span className="sr-only">{t('fee.void')}</span>,
      cell: ({ row }) => (row.original.status === 'VOID' ? null : (
        <span className="flex justify-end">
          <Button size="sm" variant="outline" data-testid="fee-invoice-void" onClick={() => onVoid(row.original.invoiceId)}>
            {t('fee.void')}<span className="sr-only">: {row.original.invoiceNo}</span>
          </Button>
        </span>
      )),
    } as ColumnDef<FeeInvoice, unknown>] : []),
  ], [t, status, mode, onVoid])
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.feeInvoiceStatus} value={filterStatus} testId="fee-invoice-status-filter"
          only={INVOICE_STATUSES} onChange={(value) => set({ status: value })} />
        {filterStatus && (
          <Button variant="ghost" size="sm" onClick={() => set({ status: null })}>{t('fee.all')}</Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('fee.invoices.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.invoiceId} empty={<EmptyState art="money" title={t('fee.invoices.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-invoice-row', 'data-id': row.invoiceId, 'data-status': row.status })} />
        )}
    </div>
  )
}

/** The requests (`qb.admin.fee-requests`), newest first; one under review opens its page, where the approver decides. */
function Requests() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const kind = useLabels(DICTIONARY.feeRequestKind)
  const status = useLabels(DICTIONARY.feeRequestStatus)
  const [search, setSearch] = useSearchParams()
  const filterStatus = search.get('status')
  const set = (change: Record<string, string | null>) => {
    const next = new URLSearchParams(search)
    for (const [key, value] of Object.entries(change)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    setSearch(next, { replace: true })
  }
  const more = useMoreFilters([
    { kind: 'dictionary', name: 'kind', label: t('fee.kind'), dictionary: DICTIONARY.feeRequestKind },
    { kind: 'eq', name: 'sponsorId', label: t('fee.sponsor') },
    { kind: 'time', name: 'requestedAt', label: t('fee.requestedAt') },
  ], 'fee-request-filters')
  const { query, table, sortable } = usePaged([...FEES_KEY, 'requests', filterStatus, more.filters],
    (offset, limit, sorts) => readFeeRequests(filterStatus, offset, limit, sorts, more.filters), true, REQUEST_SORTS)
  const columns = useMemo<ColumnDef<FeeRequest, unknown>[]>(() => [
    { id: 'requestedAt', header: t('fee.requestedAt'), cell: ({ row }) => when(row.original.requestedAt) },
    { id: 'kind', header: t('fee.kind'),
      cell: ({ row }) => <span data-testid="fee-request-kind">{kind(row.original.kind)}</span> },
    { id: 'sponsor', header: t('fee.sponsor'),
      cell: ({ row }) => <SponsorCell sponsorId={row.original.sponsorId} company={row.original.company} /> },
    { id: 'amount', header: t('fee.amount'), meta: right,
      cell: ({ row }) => (row.original.amount ? money(row.original.amount) : '') },
    { id: 'reason', header: t('fee.reason'), cell: ({ row }) => row.original.reason },
    { id: 'status', header: t('fee.status'),
      cell: ({ row }) => <StatusBadge code={row.original.status} label={status(row.original.status)} testId="fee-request-status" /> },
    { id: 'requestedBy', header: t('fee.requestedBy'),
      cell: ({ row }) => (row.original.requestedBy ? <UserName id={row.original.requestedBy} /> : '') },
    {
      id: 'actions',
      header: () => <span className="sr-only">{t('fee.open')}</span>,
      cell: ({ row }) => (
        <span className="flex justify-end">
          <Button size="sm" variant={row.original.status === REQUEST_REVIEW ? 'default' : 'outline'} asChild>
            <Link to={PATH.feeRequest(row.original.requestId)} data-testid="fee-request-open">
              {row.original.status === REQUEST_REVIEW ? t('fee.review') : t('fee.open')}
            </Link>
          </Button>
        </span>
      ),
    },
  ], [t, kind, status])
  return (
    <div className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end gap-4" role="group" aria-label={t('finance.filter.label')}>
        <StatusFilter dictionary={DICTIONARY.feeRequestStatus} value={filterStatus} testId="fee-request-status-filter"
          only={REQUEST_STATUSES} onChange={(value) => set({ status: value })} />
        {filterStatus && (
          <Button variant="ghost" size="sm" onClick={() => set({ status: null })}>{t('fee.all')}</Button>
        )}
      </div>
      {more.bar}
      {query.isError
        ? <LoadFailed error={query.error} onRetry={() => void query.refetch()} />
        : (
          <DataTable label={t('fee.requests.table')} columns={sortable(columns)} {...table}
            getRowId={(row) => row.requestId} empty={<EmptyState art="inbox" title={t('fee.requests.none')} />}
            rowProps={(row) => ({ 'data-testid': 'fee-request-row', 'data-id': row.requestId, 'data-status': row.status })} />
        )}
    </div>
  )
}

/**
 * The platform's fees (docs/quizbuks/plans/Q87-fee-account.md): the charges, the invoices and the requests, for
 * finance. Finance records a fee by hand or an invoice voided; another administrator (the bank top-ups' approvers)
 * decides it on the request's page. Everything else happens on the server: the monthly charge, the invoices, the
 * reminders and the payments from the sponsors' cash accounts.
 */
export default function FeesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const [search, setSearch] = useSearchParams()
  const approver = can(PERMISSION.bankApprove)
  const manager = can(PERMISSION.feeManage)
  const asked = search.get('tab')
  const tab: Tab = asked === 'invoices' || asked === 'grants' || (asked === 'requests' && approver) ? asked : 'charges'
  const [recording, setRecording] = useState<{ kind: RequestKind; invoiceId?: string } | null>(null)
  return (
    <div className="flex flex-col gap-4" data-testid="fees-page">
      <PageHeader title={t('fee.title')} description={t('fee.description')} className="pb-0"
        actions={manager && (
          <span className="flex flex-wrap gap-2">
            <Button variant="outline" onClick={() => setRecording({ kind: 'VOID' })} data-testid="fee-new-void">
              {t('fee.newVoid')}
            </Button>
            <Button variant="outline" onClick={() => setRecording({ kind: 'CREDIT' })} data-testid="fee-new-credit">
              {t('fee.newCredit')}
            </Button>
            <Button onClick={() => setRecording({ kind: 'MANUAL' })} data-testid="fee-new-manual">{t('fee.newManual')}</Button>
          </span>
        )} />
      <Tabs value={tab} onValueChange={(value) => setSearch(value === 'charges' ? {} : { tab: value }, { replace: true })}>
        <TabsList>
          <TabsTrigger value="charges" data-testid="tab-charges">{t('fee.tabs.charges')}</TabsTrigger>
          <TabsTrigger value="invoices" data-testid="tab-invoices">{t('fee.tabs.invoices')}</TabsTrigger>
          {approver && <TabsTrigger value="requests" data-testid="tab-requests">{t('fee.tabs.requests')}</TabsTrigger>}
          <TabsTrigger value="grants" data-testid="tab-grants">{t('fee.tabs.grants')}</TabsTrigger>
        </TabsList>
        <TabsContent value="charges">{tab === 'charges' && <Charges />}</TabsContent>
        <TabsContent value="invoices">
          {tab === 'invoices' && <Invoices onVoid={manager ? (invoiceId) => setRecording({ kind: 'VOID', invoiceId }) : null} />}
        </TabsContent>
        {approver && <TabsContent value="requests">{tab === 'requests' && <Requests />}</TabsContent>}
        <TabsContent value="grants">{tab === 'grants' && <FeeCreditGrants />}</TabsContent>
      </Tabs>
      {recording && <RequestDialog kind={recording.kind} invoiceId={recording.invoiceId} onClose={() => setRecording(null)} />}
    </div>
  )
}
