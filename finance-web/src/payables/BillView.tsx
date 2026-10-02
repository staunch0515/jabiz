import { useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ApiError,
  ApprovalPanel,
  EXTENSION_NAMESPACE,
  formatAmount,
  formatDate,
  formatDecimal,
  paths,
  runProcess,
  useAuth,
} from '@jabiz/admin'
import { Alert, App, Button, Card, Descriptions, Input, Space, Table, Tag, Typography } from 'antd'
import { useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { rescale, significantScale, stored } from '../receivables/money'
import {
  DATASETS,
  loadVendors,
  PERMISSIONS,
  PROCESSES,
  type ApApplication,
  type BillTax,
  type LoadedBill,
  type StoredBillLine,
} from './api'
import { billPath, BILLS_PATH } from './paths'
import { ApprovalTag, BillStatusTag } from './StatusTags'

const money = (value: unknown) => formatAmount(value as number | string | null | undefined, { scale: 2 })

/** A rate as entered: "8.25%". */
function percent(value: unknown): string {
  const d = stored(value)
  return `${formatDecimal(rescale(d, significantScale(d)))}%`
}

/**
 * A posted or void bill or vendor credit, read-only: its facts and totals with the approval it waits for (the
 * approver decides here), its lines with their 1099 boxes, how its use tax was computed (FIN-TX-007, FIN-UI-007),
 * what paid or credited it, and the void (who may void bills).
 */
export default function BillView({ loaded, warnings }: { loaded: LoadedBill; warnings: string[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { bill } = loaded
  const vendors = useQuery({ queryKey: ['fin', 'vendors'], queryFn: loadVendors, staleTime: 60_000 })
  const vendor = vendors.data?.find((v) => v.vendorCode === bill.vendorCode)
  const credit = bill.kind === 'CREDIT'
  const title = t(credit ? 'payables.titleCredit' : 'payables.titleBill', { number: bill.billNo ?? '' })

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }} data-testid="bill-view">
      <Space align="center" wrap>
        <Link to={BILLS_PATH}>{t('payables.backToBills')}</Link>
        <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{title}</Typography.Title>
        <BillStatusTag status={bill.status} />
        <ApprovalTag approval={bill.approval} />
      </Space>
      {warnings.map((text) => <Alert key={text} type="warning" showIcon message={text} />)}

      {bill.approval === 'PENDING' && bill.approvalRequestId && can(PERMISSIONS.decide) && (
        <Card size="small" title={t('payables.facts.approval')}>
          <ApprovalPanel requestId={bill.approvalRequestId}
            onDecided={() => void queryClient.invalidateQueries({ queryKey: ['fin', 'bill', bill.billId] })} />
        </Card>
      )}

      <Descriptions size="small" bordered column={{ xs: 1, md: 3 }} data-testid="bill-facts">
        <Descriptions.Item label={t('payables.vendor')}>
          {bill.vendorCode}{vendor ? ` — ${vendor.legalName}` : ''}
        </Descriptions.Item>
        <Descriptions.Item label={t('payables.vendorInvoiceNo')}>{bill.vendorInvoiceNo}</Descriptions.Item>
        <Descriptions.Item label={t('payables.invoiceDate')}>{formatDate(bill.invoiceDate)}</Descriptions.Item>
        <Descriptions.Item label={t('payables.receivedDate')}>
          {bill.receivedDate ? formatDate(bill.receivedDate) : '—'}
        </Descriptions.Item>
        <Descriptions.Item label={t('payables.dueDate')}>{bill.dueDate ? formatDate(bill.dueDate) : '—'}</Descriptions.Item>
        <Descriptions.Item label={t('payables.terms')}>{bill.termsCode ?? '—'}</Descriptions.Item>
        <Descriptions.Item label={t('payables.facts.subtotal')}>{money(bill.subtotal)}</Descriptions.Item>
        <Descriptions.Item label={t('payables.facts.useTax')}>
          <span data-testid="bill-use-tax">{money(bill.useTaxTotal)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={`${t('payables.facts.total')} (${bill.currency})`}>
          <span data-testid="bill-total">{money(bill.total)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('payables.facts.open')}>
          <span data-testid="bill-open">{money(bill.openAmount)}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('payables.facts.glNo')}>
          <span data-testid="bill-gl-no">{bill.glNo ?? '—'}</span>
        </Descriptions.Item>
        <Descriptions.Item label={t('payables.facts.preparedBy')}>{bill.preparedBy ?? '—'}</Descriptions.Item>
        {bill.duplicateReason && (
          <Descriptions.Item label={t('payables.duplicateReason')} span={3}>{bill.duplicateReason}</Descriptions.Item>
        )}
        {bill.description && (
          <Descriptions.Item label={t('payables.description')} span={2}>{bill.description}</Descriptions.Item>
        )}
        <Descriptions.Item label={t('payables.facts.history')}>
          <Link to={paths.history(DATASETS.bill, bill.billId)}>{t('payables.facts.viewHistory')}</Link>
        </Descriptions.Item>
        {bill.voidDate && (
          <Descriptions.Item label={t('payables.voidTitle')} span={3}>
            {t('payables.facts.voided', { date: formatDate(bill.voidDate), reason: bill.voidReason ?? '' })}
          </Descriptions.Item>
        )}
      </Descriptions>

      <Card size="small" title={t('payables.lines')}>
        <LinesTable lines={loaded.lines} />
      </Card>
      <UseTax taxes={loaded.taxes} />
      <Applications applications={loaded.applications} />
      <VoidCard loaded={loaded} />
    </Space>
  )
}

function LinesTable({ lines }: { lines: StoredBillLine[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Table<StoredBillLine>
      size="small"
      rowKey={(line) => String(line.lineNo)}
      pagination={false}
      dataSource={lines}
      data-testid="bill-view-lines"
      columns={[
        { title: t('payables.column.line'), dataIndex: 'lineNo', align: 'right', width: 60 },
        { title: t('payables.column.description'), dataIndex: 'description' },
        { title: t('payables.column.account'), dataIndex: 'account' },
        { title: t('payables.column.department'), dataIndex: 'department', render: (v: string | null) => v ?? '' },
        { title: t('payables.column.useTaxCode'), dataIndex: 'useTaxCode', render: (v: string | null) => v ?? '' },
        {
          title: t('payables.column.form1099'),
          dataIndex: 'form1099',
          render: (form: string | null, line) => (form ? `1099-${form} ${t('payables.box')} ${line.box1099 ?? ''}` : ''),
        },
        { title: t('payables.column.amount'), dataIndex: 'amount', align: 'right', render: money },
      ]}
    />
  )
}

/**
 * How the use tax came about (FIN-TX-007, FIN-UI-007): for each taxed line and jurisdiction the base, the rate, the
 * day that rate took effect and the tax accrued; none when the vendor charged the tax or the purchase is not taxed.
 */
function UseTax({ taxes }: { taxes: BillTax[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const sorted = [...taxes].sort((a, b) => Number(a.lineNo ?? 0) - Number(b.lineNo ?? 0)
    || (a.jurisdiction ?? '').localeCompare(b.jurisdiction ?? ''))
  return (
    <Card size="small" title={t('payables.useTaxTitle')} data-testid="use-tax">
      {taxes.length === 0 ? (
        <Typography.Text type="secondary">{t('payables.noUseTax')}</Typography.Text>
      ) : (
        <Table<BillTax>
          size="small"
          rowKey="taxId"
          pagination={false}
          dataSource={sorted}
          columns={[
            { title: t('payables.column.line'), dataIndex: 'lineNo', align: 'right', width: 60 },
            { title: t('payables.taxCode'), dataIndex: 'taxCode' },
            { title: t('payables.taxJurisdiction'), dataIndex: 'jurisdiction' },
            { title: t('payables.taxBase'), dataIndex: 'base', align: 'right', render: money },
            { title: t('payables.taxRate'), dataIndex: 'ratePercent', align: 'right',
              render: (value: unknown) => (value === null || value === undefined ? '—' : percent(value)) },
            { title: t('payables.taxRateFrom'), dataIndex: 'rateFrom',
              render: (value: string | null) => (value ? formatDate(value) : '—') },
            { title: t('payables.tax'), dataIndex: 'tax', align: 'right', render: money },
          ]}
        />
      )}
    </Card>
  )
}

function Applications({ applications }: { applications: ApApplication[] }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Card size="small" title={t('payables.applicationsTitle')}>
      <Table<ApApplication>
        size="small"
        rowKey="applicationId"
        pagination={false}
        dataSource={applications}
        data-testid="bill-applications"
        locale={{ emptyText: t('payables.noApplications') }}
        columns={[
          { title: t('payables.appliedDate'), dataIndex: 'applicationDate', render: (v: string) => formatDate(v) },
          {
            title: t('payables.appliedSource'),
            dataIndex: 'sourceNo',
            render: (value: string | null, row) => {
              const text = `${t(`payables.sourceKinds.${row.sourceKind}`, row.sourceKind)} ${value ?? ''}`
              return row.sourceKind === 'CREDIT' ? <Link to={billPath(row.sourceId)}>{text}</Link> : text
            },
          },
          { title: t('payables.appliedAmount'), dataIndex: 'amount', align: 'right', render: money },
          { title: t('payables.appliedDiscount'), dataIndex: 'discount', align: 'right',
            render: (value: unknown) => (value === null || value === undefined ? '' : money(value)) },
          {
            title: t('payables.appliedReason'),
            dataIndex: 'reason',
            render: (value: string | null, row) => (
              <>
                {row.reversesApplicationId && <Tag>{t('payables.reversal')}</Tag>}
                {value ?? ''}
              </>
            ),
          },
        ]}
      />
    </Card>
  )
}

/** The void of a posted bill or credit: on a day, with why; refused once something was paid or applied. */
function VoidCard({ loaded }: { loaded: LoadedBill }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const { can } = useAuth()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const { bill } = loaded
  const [voidDate, setVoidDate] = useState('')
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  if (bill.status !== 'POSTED' || !can(PERMISSIONS.voidBill)) return null

  const onVoid = async () => {
    setBusy(true)
    setError(null)
    try {
      await runProcess(PROCESSES.void, { billId: bill.billId, voidDate, reason: reason.trim() })
      message.success(t('payables.voidDone', { number: bill.billNo }))
      await queryClient.invalidateQueries({ queryKey: ['fin', 'bill', bill.billId] })
    } catch (e) {
      setError(e instanceof ApiError ? e.display : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card size="small" title={t('payables.voidTitle')}>
      <Space direction="vertical" style={{ width: '100%' }}>
        {error && <Alert type="error" showIcon message={error} data-testid="void-error" />}
        <Space wrap>
          <Input type="date" value={voidDate} onChange={(e) => setVoidDate(e.target.value)}
            aria-label={t('payables.voidDate')} style={{ width: 170 }} />
          <Input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={500}
            aria-label={t('payables.voidReason')} placeholder={t('payables.voidReason')} style={{ width: 320 }} />
          <Button danger disabled={busy || !voidDate || !reason.trim()} onClick={() => void onVoid()} data-testid="bill-void">
            {t('payables.voidTitle')}
          </Button>
        </Space>
      </Space>
    </Card>
  )
}
