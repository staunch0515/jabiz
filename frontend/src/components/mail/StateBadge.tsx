import { LANGUAGE_NAMES } from '@jabiz/client'
import { Badge } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import type { MailTextState } from '../../api/mail'

const STATE_VARIANT: Record<MailTextState | 'PENDING', 'outline' | 'secondary' | 'warning' | 'destructive'> = {
  DEFAULT: 'outline',
  CUSTOM: 'secondary',
  DEFAULT_UPDATED: 'warning',
  INVALID: 'destructive',
  UNAPPROVED: 'destructive',
  PENDING: 'warning',
}

/** The state of one language's texts as a badge: the language's name and the state, in words (not only colour). */
export default function StateBadge({ locale, state }: { locale: string; state: MailTextState | 'PENDING' }) {
  const { t } = useTranslation()
  return (
    <Badge variant={STATE_VARIANT[state]} data-testid={`mail-state-${locale}`} data-state={state}>
      <span lang={locale}>{LANGUAGE_NAMES[locale as keyof typeof LANGUAGE_NAMES] ?? locale}</span>
      <span aria-hidden>·</span>
      {t(`mail.state.${state}`)}
    </Badge>
  )
}
