import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import type { RecStatus } from './api'

const COLORS: Record<RecStatus, string> = { PREPARED: 'default', SUBMITTED: 'processing', SIGNED_OFF: 'success' }

export function RecStatusTag({ status }: { status: RecStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={COLORS[status]} data-testid="rec-status">{t(`bank.recStatuses.${status}`, status)}</Tag>
}
