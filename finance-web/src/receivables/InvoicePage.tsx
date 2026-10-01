import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ApiError, ApprovalPanel, EXTENSION_NAMESPACE, runProcess, useAuth } from '@jabiz/admin'
import { Alert, App, Button, Card, Input, Space, Tag, Typography } from 'antd'
import { useEffect, useId, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { loadAccounts } from '../journal/api'
import {
  loadCustomers,
  loadInvoice,
  loadTaxCodes,
  PERMISSIONS,
  postInvoice,
  PROCESSES,
  saveInvoice,
  type Invoice,
  type InvoiceInput,
  type InvoiceKind,
  type InvoiceOutput,
} from './api'
import Field from './Field'
import {
  checkLine,
  fromStored,
  hasProblems,
  isBlank,
  padLines,
  placeViolations,
  toInputs,
  type InvoiceLine,
  type ServerProblems,
} from './invoice'
import InvoiceLines from './InvoiceLines'
import InvoiceView from './InvoiceView'
import { invoicePath, INVOICES_PATH } from './paths'
import { InvoiceStatusTag } from './StatusTags'

interface Header {
  kind: InvoiceKind
  customerCode: string
  invoiceDate: string
  termsCode: string
  taxCode: string
  currency: string
  reference: string
  description: string
  originalInvoiceId: string | null
}

const EMPTY_HEADER: Header = {
  kind: 'INVOICE',
  customerCode: '',
  invoiceDate: '',
  termsCode: '',
  taxCode: '',
  currency: '',
  reference: '',
  description: '',
  originalInvoiceId: null,
}

function headerOf(invoice: Invoice): Header {
  return {
    kind: invoice.kind,
    customerCode: invoice.customerCode,
    invoiceDate: invoice.invoiceDate,
    termsCode: invoice.termsCode ?? '',
    taxCode: invoice.taxCode ?? '',
    currency: invoice.currency ?? '',
    reference: invoice.reference ?? '',
    description: invoice.description ?? '',
    originalInvoiceId: invoice.originalInvoiceId ?? null,
  }
}

/**
 * An invoice or credit memo (FIN-AR-003, 004, 006; FIN-UI-002): a draft is entered and changed here by keyboard and
 * saved through FIN_INVOICE_SAVE, then posted through FIN_INVOICE_POST, which computes the tax, numbers it and books
 * it. Ctrl+S saves, Ctrl+Enter posts. A posted document is shown read-only with its tax explained, what was applied
 * to it, its documents and its corrections ({@link InvoiceView}). A new credit memo starts from the invoice it
 * credits (`?credits=`). The server decides who may do what; the page only offers it.
 */
export default function InvoicePage({ instanceKey }: { instanceKey?: string }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { message } = App.useApp()
  const { can } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { invoiceId } = useParams<{ invoiceId: string }>()
  const [search] = useSearchParams()
  const credits = invoiceId ? null : search.get('credits')
  const formId = useId()

  const customers = useQuery({ queryKey: ['fin', 'customers'], queryFn: loadCustomers, staleTime: 60_000 })
  const taxCodes = useQuery({ queryKey: ['fin', 'taxCodes'], queryFn: loadTaxCodes, staleTime: 60_000 })
  const accounts = useQuery({ queryKey: ['fin', 'accounts'], queryFn: loadAccounts, staleTime: 60_000 })
  const loaded = useQuery({
    queryKey: ['fin', 'invoice', invoiceId],
    queryFn: () => loadInvoice(invoiceId as string),
    enabled: Boolean(invoiceId),
  })
  const original = useQuery({
    queryKey: ['fin', 'invoice', credits],
    queryFn: () => loadInvoice(credits as string),
    enabled: Boolean(credits),
  })
  const invoice = loaded.data?.invoice
  const [startedNew] = useState(!invoiceId)

  const [header, setHeader] = useState<Header>(EMPTY_HEADER)
  const [lines, setLines] = useState<InvoiceLine[]>(() => padLines([], 2))
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState<'save' | 'post' | 'delete' | null>(null)
  const [server, setServer] = useState<ServerProblems | null>(null)
  const [warnings, setWarnings] = useState<string[]>([])
  const idempotency = useRef<{ key: string; body: string } | null>(null)

  // A stored draft fills the form when it arrives, unless the user is changing it.
  const shown = useRef<string | null>(null)
  useEffect(() => {
    if (!loaded.data || dirty) return
    const stamp = `${loaded.data.invoice.invoiceId}:${loaded.data.version}`
    if (shown.current === stamp) return
    shown.current = stamp
    setHeader(headerOf(loaded.data.invoice))
    setLines(padLines(fromStored(loaded.data.lines), 1))
  }, [loaded.data, dirty])

  // A new credit memo starts from the invoice it credits: its customer and its lines, to be cut down.
  const startedFrom = useRef<string | null>(null)
  useEffect(() => {
    if (!original.data || startedFrom.current === original.data.invoice.invoiceId) return
    startedFrom.current = original.data.invoice.invoiceId
    const source = original.data.invoice
    setHeader({ ...EMPTY_HEADER, kind: 'CREDIT_MEMO', customerCode: source.customerCode,
      originalInvoiceId: source.invoiceId, reference: source.invoiceNo ?? '' })
    setLines(padLines(fromStored(original.data.lines), 1))
  }, [original.data])

  const customer = customers.data?.find((c) => c.customerCode === header.customerCode.trim())
  const accountMap = accounts.data
  const taxCodeSet = useMemo(() => new Set((taxCodes.data ?? []).map((c) => c.taxCode)), [taxCodes.data])
  const problems = useMemo(() => lines.map((line) => checkLine(line, accountMap, taxCodeSet)),
    [lines, accountMap, taxCodeSet])
  const editable = (!invoice || invoice.status === 'DRAFT') && can(PERMISSIONS.prepare)

  const changeHeader = (patch: Partial<Header>) => {
    setHeader((current) => ({ ...current, ...patch }))
    setDirty(true)
    setServer(null)
  }
  const changeLines = (next: InvoiceLine[]) => {
    setLines(next)
    setDirty(true)
    setServer(null)
  }

  /** The same request retried after a failure runs once; a new one after a success is new. */
  const keyFor = (body: unknown) => {
    const text = JSON.stringify(body)
    if (idempotency.current?.body !== text) idempotency.current = { key: crypto.randomUUID(), body: text }
    return idempotency.current.key
  }

  const showRefusal = (error: unknown, rows: number[]) => {
    if (error instanceof ApiError) {
      const placed = placeViolations(error.violations, rows)
      if (placed.general.length === 0 && placed.cells.size === 0) placed.general.push(error.display)
      setServer(placed)
    } else {
      setServer({ cells: new Map(), general: [String(error)] })
    }
  }

  const save = async (): Promise<InvoiceOutput | null> => {
    const { inputs, rows } = toInputs(lines)
    const input: InvoiceInput = {
      invoiceId: invoiceId ?? undefined,
      kind: header.kind,
      customerCode: header.customerCode.trim(),
      invoiceDate: header.invoiceDate,
      termsCode: header.termsCode.trim() || null,
      taxCode: header.taxCode.trim() || null,
      currency: header.currency.trim() || null,
      reference: header.reference.trim() || null,
      description: header.description.trim() || null,
      originalInvoiceId: header.originalInvoiceId,
      lines: inputs,
    }
    try {
      const output = await saveInvoice(input, keyFor({ save: input }))
      idempotency.current = null
      setLines((current) => padLines(current.filter((line) => !isBlank(line)), 1))
      setDirty(false)
      setServer(null)
      setWarnings(output.warnings ?? [])
      shown.current = null
      await queryClient.invalidateQueries({ queryKey: ['fin', 'invoice', output.invoiceId] })
      if (!invoiceId) navigate(invoicePath(output.invoiceId), { replace: true, state: { pageInstance: instanceKey } })
      return output
    } catch (error) {
      showRefusal(error, rows)
      return null
    }
  }

  const onSave = async () => {
    if (busy || !editable) return
    setBusy('save')
    try {
      if (await save()) message.success(t('receivables.saved'))
    } finally {
      setBusy(null)
    }
  }

  const onPost = async () => {
    if (busy || !editable) return
    setBusy('post')
    try {
      let id = invoiceId
      let rows = toInputs(lines).rows
      if (!id || dirty) {
        const saved = await save()
        if (!saved) return
        id = saved.invoiceId
        rows = toInputs(lines).inputs.map((_, i) => i)
      }
      try {
        const output = await postInvoice(id as string, keyFor({ post: id }))
        idempotency.current = null
        setWarnings(output.warnings ?? [])
        message.success(output.status === 'POSTED' ? t('receivables.posted', { number: output.invoiceNo })
          : t('receivables.held'))
        shown.current = null
        await queryClient.cancelQueries({ queryKey: ['fin', 'invoice', id] })
        await queryClient.invalidateQueries({ queryKey: ['fin', 'invoice', id] })
      } catch (error) {
        showRefusal(error, rows)
      }
    } finally {
      setBusy(null)
    }
  }

  const onDelete = async () => {
    if (!invoiceId || busy) return
    setBusy('delete')
    try {
      await runProcess(PROCESSES.delete, { invoiceId })
      message.success(t('receivables.deleted'))
      navigate(INVOICES_PATH)
    } catch (error) {
      showRefusal(error, [])
    } finally {
      setBusy(null)
    }
  }

  const onKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    const ctrl = event.ctrlKey || event.metaKey
    if (ctrl && event.key.toLowerCase() === 's') {
      event.preventDefault()
      void onSave()
    } else if (ctrl && event.key === 'Enter') {
      event.preventDefault()
      void onPost()
    }
  }

  if (invoiceId && loaded.isLoading && !startedNew) return <Card loading />
  if (invoiceId && loaded.error) {
    return <Alert type="error" showIcon message={loaded.error instanceof ApiError ? loaded.error.display : String(loaded.error)} />
  }

  const creditMemo = header.kind === 'CREDIT_MEMO'
  const number = invoice?.invoiceNo
  const title = number
    ? t(creditMemo ? 'receivables.titleCreditMemo' : 'receivables.titleInvoice', { number })
    : t(creditMemo ? 'receivables.titleNewCreditMemo' : 'receivables.titleNewInvoice')

  if (invoice && loaded.data && invoice.status !== 'DRAFT') {
    return <InvoiceView loaded={loaded.data} warnings={warnings} />
  }

  const fromCustomer = (value: string | undefined) => (value ? t('receivables.fromCustomer', { value }) : undefined)

  return (
    <div onKeyDown={onKeyDown} data-testid="invoice-page">
      <Space direction="vertical" size="middle" style={{ width: '100%' }}>
        <Space align="center" wrap>
          <Link to={INVOICES_PATH}>{t('receivables.backToInvoices')}</Link>
          <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{title}</Typography.Title>
          {invoice && <InvoiceStatusTag status={invoice.status} />}
          {dirty && <Tag>{t('receivables.unsaved')}</Tag>}
        </Space>

        {invoice?.approval === 'PENDING' && invoice.approvalRequestId && can(PERMISSIONS.decide) && (
          <Card size="small" title={t('receivables.facts.approval')}>
            <ApprovalPanel requestId={invoice.approvalRequestId}
              onDecided={() => void queryClient.invalidateQueries({ queryKey: ['fin', 'invoice', invoiceId] })} />
          </Card>
        )}

        <Card size="small">
          <datalist id={`${formId}-customers`}>
            {(customers.data ?? []).filter((c) => c.status === 'ACTIVE').map((c) => (
              <option key={c.customerCode} value={c.customerCode}>{c.legalName}</option>
            ))}
          </datalist>
          <Space wrap size="large" align="end">
            <Field id={`${formId}-customer`} label={t('receivables.customer')}>
              <Input id={`${formId}-customer`} list={`${formId}-customers`} value={header.customerCode}
                disabled={!editable || Boolean(invoice) || Boolean(header.originalInvoiceId)} style={{ width: 160 }}
                autoFocus={!invoiceId} data-testid="invoice-customer"
                onChange={(e) => changeHeader({ customerCode: e.target.value.toUpperCase() })} />
            </Field>
            <Typography.Text data-testid="invoice-customer-name" style={{ minWidth: 160 }}>
              {customer?.legalName ?? ''}
            </Typography.Text>
            <Field id={`${formId}-date`} label={t('receivables.invoiceDate')}>
              <Input id={`${formId}-date`} type="date" value={header.invoiceDate} disabled={!editable}
                style={{ width: 170 }} data-testid="invoice-date"
                onChange={(e) => changeHeader({ invoiceDate: e.target.value })} />
            </Field>
            <Field id={`${formId}-reference`} label={t('receivables.reference')}>
              <Input id={`${formId}-reference`} value={header.reference} disabled={!editable} maxLength={100}
                style={{ width: 180 }} onChange={(e) => changeHeader({ reference: e.target.value })} />
            </Field>
            {!creditMemo && (
              <Field id={`${formId}-terms`} label={t('receivables.terms')}>
                <Input id={`${formId}-terms`} value={header.termsCode} disabled={!editable} maxLength={20}
                  style={{ width: 150 }} placeholder={fromCustomer(customer?.termsCode)}
                  onChange={(e) => changeHeader({ termsCode: e.target.value.toUpperCase() })} />
              </Field>
            )}
            <Field id={`${formId}-tax`} label={t('receivables.taxCode')}>
              <Input id={`${formId}-tax`} value={header.taxCode} disabled={!editable || creditMemo} maxLength={20}
                list={`${formId}-taxcodes`} style={{ width: 150 }} placeholder={fromCustomer(customer?.taxCode)}
                onChange={(e) => changeHeader({ taxCode: e.target.value.toUpperCase() })} />
              <datalist id={`${formId}-taxcodes`}>
                {(taxCodes.data ?? []).map((c) => <option key={c.taxCode} value={c.taxCode}>{c.description}</option>)}
              </datalist>
            </Field>
            <Field id={`${formId}-currency`} label={t('receivables.currency')}>
              <Input id={`${formId}-currency`} value={header.currency} disabled={!editable || creditMemo} maxLength={3}
                style={{ width: 90 }} placeholder={customer?.currency}
                onChange={(e) => changeHeader({ currency: e.target.value.toUpperCase() })} />
            </Field>
            <Field id={`${formId}-description`} label={t('receivables.description')} grow>
              <Input id={`${formId}-description`} value={header.description} disabled={!editable} maxLength={500}
                onChange={(e) => changeHeader({ description: e.target.value })} />
            </Field>
          </Space>
          {header.originalInvoiceId && (
            <Typography.Paragraph style={{ marginTop: 8, marginBottom: 0 }}>
              {t('receivables.credits')}:{' '}
              <Link to={invoicePath(header.originalInvoiceId)}>
                {original.data?.invoice.invoiceNo ?? header.reference ?? header.originalInvoiceId}
              </Link>
            </Typography.Paragraph>
          )}
        </Card>

        {server && server.general.length > 0 && (
          <Alert type="error" showIcon data-testid="invoice-problems" message={t('receivables.refused')}
            description={<ul style={{ margin: 0, paddingLeft: 18 }}>{server.general.map((text) => <li key={text}>{text}</li>)}</ul>} />
        )}
        {warnings.map((text) => <Alert key={text} type="warning" showIcon message={t('receivables.warning', { text })} />)}

        <Card size="small" title={t('receivables.lines')}>
          <InvoiceLines
            lines={lines}
            onChange={changeLines}
            problems={problems}
            serverProblems={server?.cells}
            accounts={[...(accounts.data?.values() ?? [])].filter((a) => a.active && !a.summary)}
            taxCodes={taxCodes.data ?? []}
            readOnly={!editable}
          />
          <Typography.Text type="secondary">{t('receivables.subtotalHint')}</Typography.Text>
        </Card>

        {editable && (
          <Space wrap>
            <Button onClick={() => void onSave()} loading={busy === 'save'} disabled={busy !== null} title="Ctrl+S"
              data-testid="invoice-save">
              {t('receivables.save')}
            </Button>
            <Button type="primary" onClick={() => void onPost()} loading={busy === 'post'}
              disabled={busy !== null || hasProblems(problems)} title="Ctrl+Enter" data-testid="invoice-post">
              {t('receivables.post')}
            </Button>
            {invoice && (
              <Button danger onClick={() => void onDelete()} loading={busy === 'delete'} disabled={busy !== null}>
                {t('receivables.delete')}
              </Button>
            )}
          </Space>
        )}
      </Space>
    </div>
  )
}
