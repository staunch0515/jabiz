import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useId, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import {
  ApiError,
  Button,
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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  Textarea,
  UserName,
} from '@jabiz/admin'
import { LoadFailed, Section, StatusBadge } from './components'
import {
  askCharge,
  askVoid,
  BILLING_MODES,
  FEES_KEY,
  INVOICE_STATUSES,
  readInvoices,
  readOperatorBilling,
  readRequests,
  setBilling,
  wholeOf,
  type AdminInvoice,
  type BillingMode,
  type InvoiceStatus,
  type SponsorBilling,
} from './fees-api'
import { money, when } from './hooks'

/*
 * The platform fees (Q87b, docs/quizbuks/plans/Q87-fee-account.md §11): finance's invoices, charges by hand and
 * voidings (another administrator approves each in the approvals inbox), and sponsors' billing; an operator's own
 * sponsors' billing. The pages only send what is typed; the server checks who may and what is accepted.
 */

const REASON_MAX = 500
const ALL = 'ALL'
const problemOf = (e: unknown) => (e instanceof ApiError ? e.display : String(e))

/** A sponsor's billing mode and credit, with the reason (finance for any sponsor, an operator for its own). */
function BillingForm({ sponsorId, initial, onSaved }: {
  sponsorId?: string
  initial?: Pick<SponsorBilling, 'billingMode' | 'creditLimit'>
  onSaved?: () => void
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [sponsor, setSponsor] = useState(sponsorId ?? '')
  const [mode, setMode] = useState<BillingMode>(initial?.billingMode ?? 'PREPAID')
  const [credit, setCredit] = useState(String(initial?.creditLimit ?? 0))
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const amount = mode === 'POSTPAID' ? wholeOf(credit) : 0
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || amount === null || !sponsor.trim() || !reason.trim()) return
    setBusy(true)
    setProblem(null)
    try {
      await setBilling(sponsor.trim(), mode, amount, reason.trim())
      notify.success(t('fees.billingSaved'))
      setReason('')
      await queryClient.invalidateQueries({ queryKey: FEES_KEY })
      onSaved?.()
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-3"
      data-testid="billing-form">
      <div className="flex flex-wrap items-end gap-4">
        {sponsorId === undefined && (
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-sponsor`}>{t('fees.sponsorId')}</Label>
            <Input id={`${id}-sponsor`} className="w-80" value={sponsor} maxLength={128}
              data-testid="billing-sponsor" onChange={(event) => setSponsor(event.target.value)} />
          </div>
        )}
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-mode`}>{t('fees.mode')}</Label>
          <Select value={mode} onValueChange={(value) => setMode(value as BillingMode)}>
            <SelectTrigger id={`${id}-mode`} className="w-48" data-testid="billing-mode"><SelectValue /></SelectTrigger>
            <SelectContent>
              {BILLING_MODES.map((m) => <SelectItem key={m} value={m}>{t(`fees.modes.${m}`)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
        {mode === 'POSTPAID' && (
          <div className="flex flex-col gap-2">
            <Label htmlFor={`${id}-credit`}>{t('fees.credit')}</Label>
            <Input id={`${id}-credit`} inputMode="numeric" maxLength={10} className="w-40" value={credit}
              aria-invalid={amount === null || undefined} data-testid="billing-credit"
              onChange={(event) => setCredit(event.target.value)} />
          </div>
        )}
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-reason`}>{t('fees.reason')}</Label>
        <Textarea id={`${id}-reason`} rows={2} maxLength={REASON_MAX} value={reason} data-testid="billing-reason"
          onChange={(event) => setReason(event.target.value)} />
      </div>
      {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
      <div>
        <Button type="submit" disabled={busy || amount === null || !sponsor.trim() || !reason.trim()}
          aria-busy={busy || undefined} data-testid="billing-save">{t('fees.saveBilling')}</Button>
      </div>
    </form>
  )
}

/** Asks for a charge by hand (another administrator approves). */
function ChargeForm() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [sponsor, setSponsor] = useState('')
  const [text, setText] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)
  const amount = wholeOf(text)
  const ready = amount !== null && amount > 0 && sponsor.trim() !== '' && reason.trim() !== ''
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy || !ready) return
    setBusy(true)
    setProblem(null)
    try {
      await askCharge(sponsor.trim(), amount, reason.trim())
      notify.success(t('fees.asked'))
      setText('')
      setReason('')
      await queryClient.invalidateQueries({ queryKey: FEES_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    } finally {
      setBusy(false)
    }
  }
  return (
    <form noValidate onSubmit={(event) => void submit(event)} className="flex flex-col gap-3"
      data-testid="charge-form">
      <div className="flex flex-wrap items-end gap-4">
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-sponsor`}>{t('fees.sponsorId')}</Label>
          <Input id={`${id}-sponsor`} className="w-80" value={sponsor} maxLength={128} data-testid="charge-sponsor"
            onChange={(event) => setSponsor(event.target.value)} />
        </div>
        <div className="flex flex-col gap-2">
          <Label htmlFor={`${id}-amount`}>{t('fees.amount')}</Label>
          <Input id={`${id}-amount`} inputMode="numeric" maxLength={10} className="w-40" value={text}
            aria-invalid={(text !== '' && (amount === null || amount === 0)) || undefined}
            data-testid="charge-amount" onChange={(event) => setText(event.target.value)} />
        </div>
      </div>
      <div className="flex flex-col gap-2">
        <Label htmlFor={`${id}-reason`}>{t('fees.reason')}</Label>
        <Textarea id={`${id}-reason`} rows={2} maxLength={REASON_MAX} value={reason} data-testid="charge-reason"
          onChange={(event) => setReason(event.target.value)} />
      </div>
      {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
      <div>
        <Button type="submit" disabled={busy || !ready} aria-busy={busy || undefined} data-testid="charge-ask">
          {t('fees.askCharge')}
        </Button>
      </div>
    </form>
  )
}

/** Finance: the invoices (voiding asked from a row), charges by hand, billing settings and the requests. */
export function FeesPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const id = useId()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState<InvoiceStatus | typeof ALL>(ALL)
  const [voiding, setVoiding] = useState<AdminInvoice | null>(null)
  const [reason, setReason] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const invoices = useQuery({ queryKey: [...FEES_KEY, 'invoices', status],
    queryFn: () => readInvoices(status === ALL ? null : status) })
  const requests = useQuery({ queryKey: [...FEES_KEY, 'requests'], queryFn: readRequests })
  const askVoiding = async (event: FormEvent) => {
    event.preventDefault()
    if (!voiding || !reason.trim()) return
    setProblem(null)
    try {
      await askVoid(voiding, reason.trim())
      notify.success(t('fees.asked'))
      setVoiding(null)
      setReason('')
      await queryClient.invalidateQueries({ queryKey: FEES_KEY })
    } catch (e) {
      setProblem(problemOf(e))
    }
  }
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('fees.title')} description={t('fees.description')} />
      <Section title={t('fees.invoices')} testId="fees-invoices" actions={
        <div className="flex items-center gap-2">
          <Label htmlFor={`${id}-status`} className="sr-only">{t('fees.status')}</Label>
          <Select value={status} onValueChange={(value) => setStatus(value as InvoiceStatus | typeof ALL)}>
            <SelectTrigger id={`${id}-status`} className="w-40" data-testid="fees-status"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value={ALL}>{t('fees.allStatuses')}</SelectItem>
              {INVOICE_STATUSES.map((s) => <SelectItem key={s} value={s}>{t(`fees.statuses.${s}`)}</SelectItem>)}
            </SelectContent>
          </Select>
        </div>
      }>
        {voiding && (
          <form noValidate onSubmit={(event) => void askVoiding(event)} className="flex flex-col gap-2 rounded-md border p-3"
            data-testid="void-form">
            <Label htmlFor={`${id}-void`}>{t('fees.voidReason', { invoiceNo: voiding.invoiceNo })}</Label>
            <Textarea id={`${id}-void`} rows={2} maxLength={REASON_MAX} value={reason} data-testid="void-reason"
              onChange={(event) => setReason(event.target.value)} />
            {problem && <p className="text-destructive text-sm" role="alert">{problem}</p>}
            <div className="flex gap-2">
              <Button type="submit" variant="destructive" disabled={!reason.trim()} data-testid="void-ask">
                {t('fees.askVoid')}
              </Button>
              <Button type="button" variant="outline" onClick={() => { setVoiding(null); setProblem(null) }}>
                {t('fees.cancel')}
              </Button>
            </div>
          </form>
        )}
        {invoices.isError
          ? <LoadFailed error={invoices.error} onRetry={() => void invoices.refetch()} />
          : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('fees.number')}</TableHead>
                  <TableHead>{t('fees.sponsor')}</TableHead>
                  <TableHead>{t('fees.period')}</TableHead>
                  <TableHead>{t('fees.mode')}</TableHead>
                  <TableHead className="text-right">{t('fees.total')}</TableHead>
                  <TableHead className="text-right">{t('fees.due')}</TableHead>
                  <TableHead>{t('fees.dueOn')}</TableHead>
                  <TableHead>{t('fees.status')}</TableHead>
                  <TableHead><span className="sr-only">{t('fees.askVoid')}</span></TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(invoices.data ?? []).length === 0 && (
                  <TableRow><TableCell colSpan={9} className="text-muted-foreground">{t('fees.noInvoices')}</TableCell></TableRow>
                )}
                {(invoices.data ?? []).map((invoice) => (
                  <TableRow key={invoice.invoiceId} data-testid="fee-invoice-row" data-invoice={invoice.invoiceId}>
                    <TableCell className="font-medium">{invoice.invoiceNo}</TableCell>
                    <TableCell>{invoice.company ?? invoice.sponsorId}</TableCell>
                    <TableCell>{invoice.period}</TableCell>
                    <TableCell>{t(`fees.modes.${invoice.billingMode}`)}</TableCell>
                    <TableCell className="text-right">{money(String(invoice.total))}</TableCell>
                    <TableCell className="text-right">{money(String(invoice.due))}</TableCell>
                    <TableCell>{invoice.dueOn ?? '—'}</TableCell>
                    <TableCell>
                      <StatusBadge code={invoice.status} label={t(`fees.statuses.${invoice.status}`)}
                        testId="fee-invoice-status" />
                      {invoice.reminders > 0 && (
                        <span className="text-muted-foreground ml-2 text-xs">
                          {t('fees.reminders', { count: invoice.reminders })}
                        </span>
                      )}
                    </TableCell>
                    <TableCell className="text-right">
                      {invoice.status !== 'VOID' && (
                        <Button size="sm" variant="outline" data-testid="fee-invoice-void"
                          onClick={() => { setVoiding(invoice); setReason(''); setProblem(null) }}>
                          {t('fees.askVoid')}<span className="sr-only">: {invoice.invoiceNo}</span>
                        </Button>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
      </Section>
      <Section title={t('fees.chargeTitle')} testId="fees-charge">
        <p className="text-muted-foreground text-sm">{t('fees.chargeHint')}</p>
        <ChargeForm />
      </Section>
      <Section title={t('fees.billingTitle')} testId="fees-billing">
        <p className="text-muted-foreground text-sm">{t('fees.billingHint')}</p>
        <BillingForm />
      </Section>
      <Section title={t('fees.requests')} testId="fees-requests">
        {requests.isError
          ? <LoadFailed error={requests.error} onRetry={() => void requests.refetch()} />
          : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{t('fees.requestedAt')}</TableHead>
                  <TableHead>{t('fees.kind')}</TableHead>
                  <TableHead>{t('fees.sponsor')}</TableHead>
                  <TableHead className="text-right">{t('fees.amount')}</TableHead>
                  <TableHead>{t('fees.reason')}</TableHead>
                  <TableHead>{t('fees.status')}</TableHead>
                  <TableHead>{t('fees.requestedBy')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {(requests.data ?? []).map((request) => (
                  <TableRow key={request.requestId} data-testid="fee-request-row">
                    <TableCell>{when(request.requestedAt)}</TableCell>
                    <TableCell>
                      {t(`fees.kinds.${request.kind}`)}{request.invoiceNo ? ` · ${request.invoiceNo}` : ''}
                    </TableCell>
                    <TableCell>{request.company ?? request.sponsorId}</TableCell>
                    <TableCell className="text-right">
                      {request.amount == null ? '—' : money(String(request.amount))}
                    </TableCell>
                    <TableCell>{request.reason}</TableCell>
                    <TableCell>
                      <StatusBadge code={request.status} label={t(`fees.requestStatuses.${request.status}`)} />
                    </TableCell>
                    <TableCell><UserName id={request.requestedBy} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
      </Section>
    </div>
  )
}

/** An operator's member: its sponsors' billing mode and credit, set one at a time with the reason. */
export function OperatorBillingPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const rows = useQuery({ queryKey: [...FEES_KEY, 'operator-billing'], queryFn: readOperatorBilling })
  const [editing, setEditing] = useState<string | null>(null)
  return (
    <div className="flex flex-col gap-6">
      <PageHeader title={t('fees.operatorTitle')} description={t('fees.operatorDescription')} />
      {rows.isError
        ? <LoadFailed error={rows.error} onRetry={() => void rows.refetch()} />
        : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>{t('fees.sponsor')}</TableHead>
                <TableHead>{t('fees.mode')}</TableHead>
                <TableHead className="text-right">{t('fees.credit')}</TableHead>
                <TableHead className="text-right">{t('fees.unpaid')}</TableHead>
                <TableHead>{t('fees.lastChange')}</TableHead>
                <TableHead><span className="sr-only">{t('fees.edit')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {(rows.data ?? []).length === 0 && (
                <TableRow><TableCell colSpan={6} className="text-muted-foreground">{t('fees.noSponsors')}</TableCell></TableRow>
              )}
              {(rows.data ?? []).flatMap((row) => [
                <TableRow key={row.sponsorId} data-testid="billing-row" data-sponsor={row.sponsorId}>
                  <TableCell className="font-medium">{row.company ?? row.sponsorId}</TableCell>
                  <TableCell>
                    {t(`fees.modes.${row.billingMode}`)}
                    {!row.own && <span className="text-muted-foreground ml-2 text-xs">{t('fees.byDefault')}</span>}
                  </TableCell>
                  <TableCell className="text-right">
                    {row.billingMode === 'POSTPAID' ? money(String(row.creditLimit)) : '—'}
                  </TableCell>
                  <TableCell className="text-right">{money(String(row.unpaid))}</TableCell>
                  <TableCell className="text-muted-foreground text-sm">
                    {row.updatedAt ? `${when(row.updatedAt)} · ${row.reason ?? ''}` : '—'}
                  </TableCell>
                  <TableCell className="text-right">
                    <Button size="sm" variant="outline" data-testid="billing-edit"
                      onClick={() => setEditing(editing === row.sponsorId ? null : row.sponsorId)}>
                      {t('fees.edit')}<span className="sr-only">: {row.company ?? row.sponsorId}</span>
                    </Button>
                  </TableCell>
                </TableRow>,
                ...(editing === row.sponsorId ? [
                  <TableRow key={`${row.sponsorId}-edit`}>
                    <TableCell colSpan={6}>
                      <BillingForm sponsorId={row.sponsorId} initial={row} onSaved={() => setEditing(null)} />
                    </TableCell>
                  </TableRow>,
                ] : []),
              ])}
            </TableBody>
          </Table>
        )}
    </div>
  )
}
