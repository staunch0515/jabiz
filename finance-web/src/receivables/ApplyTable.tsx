import { EXTENSION_NAMESPACE, formatAmount, formatDate } from '@jabiz/admin'
import { Alert, Button, Space, Table, Typography } from 'antd'
import { useTranslation } from 'react-i18next'
import type { Suggestion } from './api'
import { applied, readAmount, suggest, unapplied, type Entries } from './receipt'
import { sign, stored } from './money'
import { invoicePath } from './paths'
import { Link } from 'react-router'

export interface ApplyTableProps {
  /** What there is to apply: the receipt's amount, or what is unapplied of it. */
  amount: string
  suggestions: Suggestion[]
  loading?: boolean
  entries: Entries
  onChange: (entries: Entries) => void
  /** Whether a customer and a day are chosen, so that there is something to list. */
  ready: boolean
}

const inputStyle = { width: 110, textAlign: 'right' as const, padding: '2px 6px', font: 'inherit',
  border: '1px solid #d9d9d9', borderRadius: 2 }

/**
 * The customer's open invoices on the receipt's day, best matches first (finance.ar.receipt_suggestions), each with
 * the cash to apply and the discount to take (FIN-AR-002, 007, 008): typed, or spread as suggested. Shows what is
 * applied and what is left unapplied; more than was received is flagged.
 */
export default function ApplyTable({ amount, suggestions, loading, entries, onChange, ready }: ApplyTableProps) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const left = unapplied(amount, entries)
  const set = (invoiceId: string, patch: Partial<{ amount: string; discount: string }>) =>
    onChange({ ...entries, [invoiceId]: { ...(entries[invoiceId] ?? { amount: '', discount: '' }), ...patch } })

  if (!ready) return <Typography.Text type="secondary">{t('receivables.receipt.pickCustomer')}</Typography.Text>
  return (
    <Space direction="vertical" style={{ width: '100%' }}>
      <Space>
        <Button onClick={() => onChange(suggest(amount, suggestions))} disabled={suggestions.length === 0}
          data-testid="apply-suggested">
          {t('receivables.receipt.suggest')}
        </Button>
        <Button onClick={() => onChange({})}>{t('receivables.receipt.clear')}</Button>
      </Space>
      <Table<Suggestion>
        size="small"
        rowKey="invoiceId"
        pagination={false}
        loading={loading}
        dataSource={suggestions}
        data-testid="open-invoices"
        locale={{ emptyText: t('receivables.receipt.noOpenInvoices') }}
        columns={[
          { title: t('receivables.number'), dataIndex: 'invoiceNo',
            render: (value: string, row) => <Link to={invoicePath(row.invoiceId)}>{value}</Link> },
          { title: t('receivables.date'), dataIndex: 'invoiceDate', render: (value: string) => formatDate(value) },
          { title: t('receivables.dueDate'), dataIndex: 'dueDate',
            render: (value: string | null) => (value ? formatDate(value) : '') },
          { title: t('receivables.open'), dataIndex: 'openAmount', align: 'right',
            render: (value: number | string) => formatAmount(value, { scale: 2 }) },
          {
            title: t('receivables.receipt.discountOffered'),
            dataIndex: 'discountOffered',
            align: 'right',
            render: (value: number | string | null, row) => (value === null || value === undefined || sign(stored(value)) === 0
              ? ''
              : `${formatAmount(value, { scale: 2 })} ${row.discountUntil ? t('receivables.receipt.discountUntil', { date: formatDate(row.discountUntil) }) : ''}`),
          },
          { title: t('receivables.receipt.matched'), dataIndex: 'matched',
            render: (value: string) => t(`receivables.receipt.matchedBy.${value}`, value) },
          {
            title: t('receivables.receipt.applyAmount'),
            key: 'amount',
            align: 'right',
            render: (_, row) => (
              <input style={inputStyle} inputMode="decimal" value={entries[row.invoiceId]?.amount ?? ''}
                aria-label={t('receivables.receipt.applyAmountOf', { invoice: row.invoiceNo })}
                aria-invalid={readAmount(entries[row.invoiceId]?.amount ?? '') === undefined ? true : undefined}
                data-testid={`apply-${row.invoiceNo}`}
                onChange={(e) => set(row.invoiceId, { amount: e.target.value })} />
            ),
          },
          {
            title: t('receivables.receipt.discount'),
            key: 'discount',
            align: 'right',
            render: (_, row) => (
              <input style={inputStyle} inputMode="decimal" value={entries[row.invoiceId]?.discount ?? ''}
                aria-label={t('receivables.receipt.discountOf', { invoice: row.invoiceNo })}
                onChange={(e) => set(row.invoiceId, { discount: e.target.value })} />
            ),
          },
        ]}
      />
      <Space size="large">
        <span>{t('receivables.receipt.applied')}: <strong data-testid="applied-total">{formatAmount(applied(entries), { scale: 2 })}</strong></span>
        {sign(stored(left)) >= 0 ? (
          <span data-testid="left-unapplied">{t('receivables.receipt.leftUnapplied', { amount: formatAmount(left, { scale: 2 }) })}</span>
        ) : (
          <Alert type="error" showIcon data-testid="over-applied"
            message={t('receivables.receipt.overApplied', { amount: formatAmount(left.replace('-', ''), { scale: 2 }) })} />
        )}
      </Space>
    </Space>
  )
}
