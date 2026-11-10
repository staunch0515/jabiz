import { Badge } from '@jabiz/ui'
import { BadgeCheck } from 'lucide-react'
import { useTranslation } from 'react-i18next'
import { QB } from './messages'

/** F-M3: the mark of a company whose domain was verified, beside its name; the caller shows it only when there is one. */
export function VerifiedMark({ domain }: { domain: string }) {
  const { t } = useTranslation(QB)
  const label = t('verifiedCompany', { domain })
  return (
    <Badge variant="secondary" className="w-fit gap-1" role="group" aria-label={label} data-testid="sponsor-verified">
      <BadgeCheck aria-hidden className="size-3.5" />
      <span>{label}</span>
    </Badge>
  )
}
