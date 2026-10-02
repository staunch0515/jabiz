import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { Tag } from 'antd'
import { useTranslation } from 'react-i18next'
import type { AssetStatus, RunStatus } from './api'

const ASSET_COLORS: Record<AssetStatus, string> = {
  IN_SERVICE: 'processing', FULLY_DEPRECIATED: 'success', DISPOSED: 'default',
}
const RUN_COLORS: Record<RunStatus, string> = { POSTED: 'success', REVERSED: 'default' }

/** An asset's state: voided with its bill, unclassified, or its status. */
export function AssetStatusTag({ status, active = true, classified = true }: {
  status?: AssetStatus | null
  active?: boolean
  classified?: boolean
}) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  if (!active) return <Tag data-testid="asset-status">{t('fa.voided')}</Tag>
  if (!classified || !status) return <Tag data-testid="asset-status">{t('fa.unclassified')}</Tag>
  return <Tag color={ASSET_COLORS[status]} data-testid="asset-status">{t(`fa.assetStatuses.${status}`, status)}</Tag>
}

export function RunStatusTag({ status }: { status: RunStatus }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  return <Tag color={RUN_COLORS[status]} data-testid="run-status">{t(`fa.runStatuses.${status}`, status)}</Tag>
}
