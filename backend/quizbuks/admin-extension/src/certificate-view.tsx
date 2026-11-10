import { useTranslation } from 'react-i18next'
import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import type { CertificateRow } from './api'
import { certificateState } from './certificate-state'
import { StatusBadge } from './components'

/** The state as a badge (revoked in red; the others are told by their words, not by a colour). */
export function CertificateStateBadge({ row }: { row: Pick<CertificateRow, 'revokedAt' | 'shared' | 'validUntil'> }) {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const state = certificateState(row)
  return <StatusBadge code={state} label={t(`certificateLookup.states.${state}`)} testId="certificate-state" />
}
