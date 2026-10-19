import { MailUnsubscribe } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router'

/**
 * Where the unsubscribe link of a notification mail leads in the admin (`/mail/unsubscribe?token=…`,
 * docs/design/18-numbering-approvals-tasks.md section 5.6): @jabiz/ui's MailUnsubscribe (decision D52) on the
 * admin's router. It is the page of `jabiz.mail.unsubscribe-path`, for notifications of every entry and for the links
 * mailed before an entry had its own page, which keep working. Open to everyone, outside the sign-in.
 */
export default function MailUnsubscribePage() {
  const { t } = useTranslation()
  const location = useLocation()
  return <MailUnsubscribe search={location.search} brand={t('app.title')} appearance={false} />
}
