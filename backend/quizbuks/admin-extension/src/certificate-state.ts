import { useTranslation } from 'react-i18next'
import { EXTENSION_NAMESPACE, useLanguage } from '@jabiz/admin'
import type { CertificateRow } from './api'
import { i18nText } from './trees-api'

/** A certificate's state as support reads it: the lighting revoked, the holder's link turned off, Q77's end passed. */
export type CertificateStateCode = 'VALID' | 'REVOKED' | 'EXPIRED' | 'SHARING_OFF'

export function certificateState(row: Pick<CertificateRow, 'revokedAt' | 'shared' | 'validUntil'>,
  now: number = Date.now()): CertificateStateCode {
  if (row.revokedAt) return 'REVOKED'
  if (row.validUntil && Date.parse(row.validUntil) <= now) return 'EXPIRED'
  return row.shared ? 'VALID' : 'SHARING_OFF'
}

/** What a certificate is called, in the reader's language: a node's by its names, Q77's by its certification. */
export function useCertificateName() {
  const { t } = useTranslation(EXTENSION_NAMESPACE)
  const language = useLanguage()
  return (row: Pick<CertificateRow, 'source' | 'certification' | 'certificateNames'>): string => {
    if (row.source === 'NODE' || !row.certification) {
      return i18nText(row.certificateNames, language) || t('certificateLookup.unnamed')
    }
    return t(`certification.names.${row.certification}`, { defaultValue: row.certification })
  }
}
