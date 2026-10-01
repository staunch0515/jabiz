import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import type { InvoiceStatus, ReceiptStatus } from './api'

const INVOICE_COLORS: Record<InvoiceStatus, string> = {
  DRAFT: 'default',
  POSTED: 'success',
  VOID: 'error',
  WRITTEN_OFF: 'warning',
}

export function InvoiceStatusTag({ status }: { status: InvoiceStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={INVOICE_COLORS[status]} data-testid="invoice-status">{t(`receivables.statuses.${status}`, status)}</Tag>
}

export function ReceiptStatusTag({ status }: { status: ReceiptStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Tag color={status === 'POSTED' ? 'success' : 'error'} data-testid="receipt-status">
      {t(`receivables.receipt.statuses.${status}`, status)}
    </Tag>
  )
}
