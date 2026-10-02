import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import type { Approval, BillStatus, RunStatus } from './api'

const BILL_COLORS: Record<BillStatus, string> = { DRAFT: 'default', POSTED: 'success', VOID: 'error' }
const APPROVAL_COLORS: Record<Approval, string> = {
  NOT_REQUIRED: 'default',
  PENDING: 'processing',
  APPROVED: 'success',
  REJECTED: 'error',
}
const RUN_COLORS: Record<RunStatus, string> = {
  DRAFT: 'default',
  SUBMITTED: 'processing',
  APPROVED: 'warning',
  RELEASED: 'success',
  CANCELLED: 'error',
}

export function BillStatusTag({ status }: { status: BillStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={BILL_COLORS[status]} data-testid="bill-status">{t(`payables.statuses.${status}`, status)}</Tag>
}

export function ApprovalTag({ approval }: { approval?: Approval | null }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (!approval || approval === 'NOT_REQUIRED') return null
  return (
    <Tag color={APPROVAL_COLORS[approval]} data-testid="bill-approval">{t(`payables.approvals.${approval}`, approval)}</Tag>
  )
}

export function RunStatusTag({ status }: { status: RunStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={RUN_COLORS[status]} data-testid="run-status">{t(`payables.runStatuses.${status}`, status)}</Tag>
}
