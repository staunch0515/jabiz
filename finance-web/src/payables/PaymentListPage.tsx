import { useQuery } from '@tanstack/react-query'
import { ApiError, EXTENSION_NAMESPACE, formatAmount, formatDate } from '@jabiz/admin'
import { Alert, Input, Select, Space, Table, Tag, Typography } from 'antd'
import { useMemo, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { sum } from '../receivables/money'
import { loadPaymentRegister, METHODS, type Method, type PaymentRegisterRow } from './api'
import { thisYear } from './dates'
import { runPath } from './paths'

/**
 * The payment register (FIN-UI-004, FIN-AP-012): payments made on days in a range, by vendor, method and state, with
 * the total of those not voided; each opens its run. Template finance.ap.payment_register.
 */
export default function PaymentListPage() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const [range, setRange] = useState(thisYear)
  const [method, setMethod] = useState<Method | null>(null)
  const [status, setStatus] = useState<'POSTED' | 'VOID' | null>(null)
  const [vendorCode, setVendorCode] = useState('')
  const register = useQuery({
    queryKey: ['fin', 'payments', range.from, range.to, method, status, vendorCode],
    queryFn: () => loadPaymentRegister({ ...range, method, status, vendorCode: vendorCode.trim() }),
    enabled: Boolean(range.from && range.to),
  })
  const rows = useMemo(() => register.data?.items ?? [], [register.data])
  const total = useMemo(() => sum(rows.filter((r) => r.status === 'POSTED').map((r) => r.amount)), [rows])

  return (
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Title level={3} style={{ margin: 0 }} data-testid="page-title">{t('payables.paymentsTitle')}</Typography.Title>
      <Space wrap>
        <label>
          {t('payables.from')}{' '}
          <Input type="date" value={range.from} aria-label={t('payables.from')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, from: e.target.value }))} />
        </label>
        <label>
          {t('payables.to')}{' '}
          <Input type="date" value={range.to} aria-label={t('payables.to')} style={{ width: 170 }}
            onChange={(e) => setRange((r) => ({ ...r, to: e.target.value }))} />
        </label>
        <Input value={vendorCode} aria-label={t('payables.vendor')} placeholder={t('payables.anyVendor')}
          style={{ width: 150 }} onChange={(e) => setVendorCode(e.target.value.toUpperCase())} />
        <Select<Method | null> aria-label={t('payables.method')} style={{ width: 200 }} value={method} onChange={setMethod}
          options={[{ value: null, label: t('payables.anyMethod') },
            ...METHODS.map((m) => ({ value: m, label: t(`payables.methods.${m}`) }))]} />
        <Select<'POSTED' | 'VOID' | null> aria-label={t('payables.status')} style={{ width: 180 }} value={status}
          onChange={setStatus} options={[{ value: null, label: t('payables.allStatuses') },
            { value: 'POSTED', label: t('payables.paymentStatuses.POSTED') },
            { value: 'VOID', label: t('payables.paymentStatuses.VOID') }]} />
      </Space>
      {register.data?.total !== undefined && register.data.total > rows.length && (
        <Alert type="warning" showIcon message={t('payables.registerCapped', { shown: rows.length, total: register.data.total })} />
      )}
      <Table<PaymentRegisterRow>
        data-testid="payment-table"
        size="small"
        rowKey="paymentId"
        loading={register.isLoading}
        dataSource={rows}
        pagination={{ pageSize: 50, showSizeChanger: false }}
        locale={{ emptyText: register.error instanceof ApiError ? register.error.display : t('payables.noPayments') }}
        columns={[
          { title: t('payables.paymentNo'), dataIndex: 'paymentNo', sorter: (a, b) => a.paymentNo.localeCompare(b.paymentNo) },
          { title: t('payables.paymentDate'), dataIndex: 'paymentDate', defaultSortOrder: 'ascend',
            sorter: (a, b) => a.paymentDate.localeCompare(b.paymentDate), render: (v: string) => formatDate(v) },
          { title: t('payables.runNo'), dataIndex: 'runNo', render: (v: string, row) => <Link to={runPath(row.runId)}>{v}</Link> },
          { title: t('payables.kind'), dataIndex: 'kind', render: (v: string) => t(`payables.lineKinds.${v}`, v) },
          { title: t('payables.vendor'), dataIndex: 'vendorCode' },
          { title: t('payables.payee'), dataIndex: 'payee', ellipsis: true },
          { title: t('payables.method'), dataIndex: 'method', render: (v: string) => t(`payables.methods.${v}`, v) },
          { title: t('payables.checkNo'), dataIndex: 'checkNo' },
          { title: t('payables.amount'), dataIndex: 'amount', align: 'right',
            render: (v: number | string) => formatAmount(v, { scale: 2 }) },
          { title: t('payables.discount'), dataIndex: 'discount', align: 'right',
            render: (v: number | string | null) => (v === null || v === undefined ? '' : formatAmount(v, { scale: 2 })) },
          { title: t('payables.status'), dataIndex: 'status',
            render: (v: string, row) => (v === 'VOID'
              ? <Tag color="error">{t('payables.voidedOn', { date: row.voidDate ? formatDate(row.voidDate) : '' })}</Tag>
              : <Tag color="success">{t('payables.paymentStatuses.POSTED')}</Tag>) },
        ]}
        summary={() => (
          <Table.Summary.Row>
            <Table.Summary.Cell index={0} colSpan={8}>{t('payables.paymentCount', { count: rows.length })}</Table.Summary.Cell>
            <Table.Summary.Cell index={8} align="right">
              <span data-testid="register-total">{formatAmount(total, { scale: 2 })}</span>
            </Table.Summary.Cell>
            <Table.Summary.Cell index={9} colSpan={2} />
          </Table.Summary.Row>
        )}
      />
    </Space>
  )
}
