import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import type { JournalStatus } from './api'

const COLORS: Record<JournalStatus, string> = {
  DRAFT: 'default',
  SUBMITTED: 'processing',
  APPROVED: 'cyan',
  POSTED: 'success',
  REJECTED: 'error',
}

export default function StatusTag({ status }: { status: JournalStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return (
    <Tag color={COLORS[status]} data-testid="journal-status">
      {t(`journal.statuses.${status}`, status)}
    </Tag>
  )
}
