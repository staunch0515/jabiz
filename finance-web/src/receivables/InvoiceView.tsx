import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  DocumentPanel,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDecimal,
  paths,
  runProcess,
  useAuth,
  UserName,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Descriptions, Input, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link, useNavigate } from 'react-router'
import {
  DATASETS,
  LAYOUTS,
  loadCustomers,
  PERMISSIONS,
  PROCESSES,
  type Application,
  type InvoiceTax,
  type LoadedInvoice,
  type StoredInvoiceLine,
} from './api'
import { fromStored } from './invoice'
import { rescale, significantScale, stored } from './money'
import { invoicePath, INVOICES_PATH, newCreditMemoPath, receiptPath } from './paths'
import { InvoiceStatusTag } from './StatusTags'

/** A rate as entered: "6.25%", "2%". */
function percent(value: unknown): string {
  const d = stored(value)
  return `${formatDecimal(rescale(d, significantScale(d)))}%`
}

/**
 * A posted, void or written-off invoice or credit memo, read-only: its facts and totals, its lines, how its tax was
 * computed (FIN-UI-007), what was applied to it, its documents (FIN-AR-005) and the corrections it allows: a void,
 * a credit memo for it.
 */
export default function InvoiceView({ loaded, warnings }: { loaded: LoadedInvoice; warnings: string[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { invoice } = loaded
  const customers = useQuery({ queryKey: ['fin', 'customers'], queryFn: loadCustomers, staleTime: 60_000 })
  const customer = customers.data?.find((c) => c.customerCode === invoice.customerCode)
  const creditMemo = invoice.kind === 'CREDIT_MEMO'
  const title = t(creditMemo ? 'receivables.titleCreditMemo' : 'receivables.titleInvoice', {
    number: invoice.invoiceNo ?? '',
  })
  const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="invoice-view">
      <Space align="center" wrap>
        <Link to={INVOICES_PATH}>{t('receivables.backToInvoices')}</Link>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{title}</Typography.Title>
        <InvoiceStatusTag status={invoice.status} />
      </Space>
      {warnings.map((text) => <Alert key={text} type="warning" showIcon message={t('receivables.warning', { text })} />)}

      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }} data-testid="invoice-facts">
        <Descriptions.Item label={t('receivables.customer')}>
          {invoice.customerCode}{customer ? ` — ${customer.legalName}` : ''}
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.invoiceDate')}>{formatDate(invoice.invoiceDate)}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.dueDate')}>
          {invoice.dueDate ? formatDate(invoice.dueDate) : '—'}
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.terms')}>{invoice.termsCode}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.taxCode')}>{invoice.taxCode}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.reference')}>{invoice.reference ?? '—'}</Descriptions.Item>
        {invoice.originalInvoiceId && (
          <Descriptions.Item label={t('receivables.credits')}>
            <Link to={invoicePath(invoice.originalInvoiceId)}>{t('receivables.kinds.INVOICE')}</Link>
          </Descriptions.Item>
        )}
        <Descriptions.Item label={t('receivables.facts.subtotal')}>{money(invoice.subtotal)}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.taxTotal')}>
          <span data-testid="invoice-tax-total">{money(invoice.taxTotal)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={`${t('receivables.facts.total')} (${invoice.currency})`}>
          <span data-testid="invoice-total">{money(invoice.total)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.open')}>
          <span data-testid="invoice-open">{money(invoice.openAmount)}</span>
        </Descriptions.Item>
        {invoice.currency !== 'USD' && (
          <Descriptions.Item label={t('receivables.facts.totalUsd')}>
            {money(invoice.totalUsd)} ({t('receivables.facts.exchangeRate')} {String(invoice.exchangeRate ?? '')})
          </Descriptions.Item>
        )}
        <Descriptions.Item label={t('receivables.facts.glNo')}>
          <span data-testid="invoice-gl-no">{invoice.glNo ?? '—'}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.preparedBy')}>{invoice.preparedBy ? <UserName id={invoice.preparedBy} /> : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('receivables.facts.history')}>
          <Link to={paths.history(DATASETS.invoice, invoice.invoiceId)}>{t('receivables.facts.viewHistory')}</Link>
        </Descriptions.Item>
        {invoice.voidDate && (
          <Descriptions.Item label={t('receivables.voidTitle')} span={3}>
            {t('receivables.facts.voided', { date: formatDate(invoice.voidDate), reason: invoice.voidReason ?? '' })}
          </Descriptions.Item>
        )}
      </Descriptions>

      <Card size="small" title={t('receivables.lines')}>
        <LinesTable lines={loaded.lines} />
      </Card>

      <TaxExplanation taxes={loaded.taxes} />
      <Applications applications={loaded.applications} />

      {invoice.status === 'POSTED' && can(PERMISSIONS.issue) ? (
        <Card size="small" title={t('receivables.documentTitle')}>
          <DocumentPanel
            layoutId={creditMemo ? LAYOUTS.CREDIT_MEMO : LAYOUTS.INVOICE}
            params={{ invoiceId: invoice.invoiceId }}
            subjectId={invoice.invoiceId}
            issue={{ process: PROCESSES.issue, input: { invoiceId: invoice.invoiceId } }}
          />
        </Card>
      ) : can(PERMISSIONS.archive) ? (
        <Card size="small" title={t('receivables.documentTitle')}>
          <Link to={`/documents?subject=${encodeURIComponent(invoice.invoiceId)}`} data-testid="issued-documents">
            {t('receivables.issuedDocuments')}
          </Link>
        </Card>
      ) : null}

      <Corrections loaded={loaded} />
    </Space>
  )
}

function LinesTable({ lines }: { lines: StoredInvoiceLine[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const shown = fromStored(lines).map((line, i) => ({ ...line, lineNo: i + 1, amount: lines[i]?.amount }))
  return (
    <Table
      size="small"
      rowKey="lineNo"
      pagination={false}
      dataSource={shown}
      data-testid="invoice-view-lines"
      columns={[
        { title: t('receivables.column.line'), dataIndex: 'lineNo', align: 'right', width: 60 },
        { title: t('receivables.column.description'), dataIndex: 'description' },
        { title: t('receivables.column.quantity'), dataIndex: 'quantity', align: 'right' },
        { title: t('receivables.column.unitPrice'), dataIndex: 'unitPrice', align: 'right' },
        { title: t('receivables.column.revenueAccount'), dataIndex: 'revenueAccount' },
        { title: t('receivables.column.taxCode'), dataIndex: 'taxCode' },
        {
          title: t('receivables.column.amount'),
          dataIndex: 'amount',
          align: 'right',
          render: (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 }),
        },
      ]}
    />
  )
}

/**
 * How the tax came about (FIN-UI-007): per jurisdiction the taxable base, the rate and the day that rate took
 * effect, and the tax; per line its tax code, whether it was taxed, why not, and the certificate it rests on.
 */
function TaxExplanation({ taxes }: { taxes: InvoiceTax[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const jurisdictions = taxes.filter((x) => x.lineNo === null || x.lineNo === undefined)
    .sort((a, b) => (a.jurisdiction ?? '').localeCompare(b.jurisdiction ?? ''))
  const lines = taxes.filter((x) => x.lineNo !== null && x.lineNo !== undefined)
    .sort((a, b) => Number(a.lineNo) - Number(b.lineNo))
  const money = (value: unknown) => formatAmount(value as number | string, { scale: 2 })
  return (
    <Card size="small" title={t('receivables.taxTitle')} data-testid="tax-explanation">
      {taxes.length === 0 ? (
        <Typography.Text type="secondary">{t('receivables.noTax')}</Typography.Text>
      ) : (
        <Space direction="vertical" style={{ width: '100%' }}>
          {jurisdictions.length > 0 && (
            <Table
              size="small"
              rowKey="taxId"
              pagination={false}
              dataSource={jurisdictions}
              data-testid="tax-jurisdictions"
              columns={[
                { title: t('receivables.taxJurisdiction'), dataIndex: 'jurisdiction' },
                { title: t('receivables.taxBase'), dataIndex: 'base', align: 'right', render: money },
                { title: t('receivables.taxRate'), dataIndex: 'ratePercent', align: 'right',
                  render: (value: unknown) => (value === null || value === undefined ? '—' : percent(value)) },
                { title: t('receivables.taxRateFrom'), dataIndex: 'rateFrom',
                  render: (value: string | null) => (value ? formatDate(value) : '—') },
                { title: t('receivables.tax'), dataIndex: 'tax', align: 'right', render: money },
              ]}
            />
          )}
          <Typography.Text strong>{t('receivables.taxLines')}</Typography.Text>
          <Table
            size="small"
            rowKey="taxId"
            pagination={false}
            dataSource={lines}
            data-testid="tax-lines"
            columns={[
              { title: t('receivables.taxLine'), dataIndex: 'lineNo', align: 'right', width: 60 },
              { title: t('receivables.taxCode'), dataIndex: 'taxCode' },
              { title: t('receivables.taxKind'), dataIndex: 'taxKind',
                render: (value: string | null) => (value ? t(`receivables.taxKinds.${value}`, value) : '—') },
              { title: t('receivables.taxReason'), dataIndex: 'reason', render: (value: string | null) => value ?? '—' },
              { title: t('receivables.taxCertificate'), dataIndex: 'certificateNo',
                render: (value: string | null) => value ?? '—' },
              { title: t('receivables.taxBase'), dataIndex: 'base', align: 'right', render: money },
              { title: t('receivables.tax'), dataIndex: 'tax', align: 'right', render: money },
            ]}
          />
        </Space>
      )}
    </Card>
  )
}

function Applications({ applications }: { applications: Application[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })
  return (
    <Card size="small" title={t('receivables.applicationsTitle')}>
      <Table<Application>
        size="small"
        rowKey="applicationId"
        pagination={false}
        dataSource={applications}
        data-testid="invoice-applications"
        locale={{ emptyText: t('receivables.noApplications') }}
        columns={[
          { title: t('receivables.appliedDate'), dataIndex: 'applicationDate', render: (value: string) => formatDate(value) },
          {
            title: t('receivables.appliedSource'),
            dataIndex: 'sourceNo',
            render: (value: string | null, row) => {
              const text = `${t(`receivables.sourceKinds.${row.sourceKind}`, row.sourceKind)} ${value ?? ''}`
              if (row.sourceKind === 'RECEIPT') return <Link to={receiptPath(row.sourceId)}>{text}</Link>
              if (row.sourceKind === 'CREDIT_MEMO') return <Link to={invoicePath(row.sourceId)}>{text}</Link>
              return text
            },
          },
          { title: t('receivables.appliedAmount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('receivables.appliedDiscount'), dataIndex: 'discount', align: 'right',
            render: (value: unknown) => (value === null || value === undefined ? '' : money(value)) },
          {
            title: t('receivables.appliedReason'),
            dataIndex: 'reason',
            render: (value: string | null, row) => (
              <>
                {row.reversesApplicationId && <Tag>{t('receivables.reversal')}</Tag>}
                {value ?? ''}
              </>
            ),
          },
        ]}
      />
    </Card>
  )
}

/** A void (who may write receivables down) and a credit memo for the invoice (who may prepare one). */
function Corrections({ loaded }: { loaded: LoadedInvoice }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message } = App.useApp()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { invoice } = loaded
  const [voidDate, setVoidDate] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (invoice.status !== 'POSTED') return null
  const voiding = can(PERMISSIONS.credit)
  const crediting = invoice.kind === 'INVOICE' && can(PERMISSIONS.prepare)
  if (!voiding && !crediting) return null

  const onVoid = async () => {
    setBusy(true)
    setError(null)
    try {
      await runProcess(PROCESSES.void, { invoiceId: invoice.invoiceId, voidDate, reason: reason.trim() })
      message.success(t('receivables.voidDone', { number: invoice.invoiceNo }))
      await queryClient.invalidateQueries({ queryKey: ['fin', 'invoice', invoice.invoiceId] })
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('receivables.actions')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="correction-error" />}
        {crediting && (
          <Button onClick={() => navigate(newCreditMemoPath(invoice.invoiceId))} data-testid="new-credit-memo">
            {t('receivables.newCreditMemo')}
          </Button>
        )}
        {voiding && (
          <Space wrap>
            <Input type="date" value={voidDate} onChange={(e) => setVoidDate(e.target.value)}
              aria-label={t('receivables.voidDate')} style={{ width: 170 }} />
            <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500}
              aria-label={t('receivables.voidReason')} placeholder={t('receivables.voidReason')} style={{ width: 320 }} />
            <Button danger disabled={busy || !voidDate || !reason.trim()} onClick={() => void onVoid()}>
              {t('receivables.voidTitle')}
            </Button>
          </Space>
        )}
      </Space>
    </Card>
  )
}
