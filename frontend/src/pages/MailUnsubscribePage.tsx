import { MailUnsubscribe } from '@jabiz/ui'
import { useLocation } from 'react-router'
import { BrandTitle } from '../extension/BrandTitle'
import { extension } from '../extension'

/**
 * Where the unsubscribe link of a notification mail leads in the admin (`/mail/unsubscribe?token=…`,
 * docs/design/18-numbering-approvals-tasks.md section 5.6): @jabiz/ui's MailUnsubscribe (decision D52) on the
 * admin's router. It is the page of `jabiz.mail.unsubscribe-path`, for notifications of every entry and for the links
 * mailed before an entry had its own page, which keep working. Open to everyone, outside the sign-in.
 */
export default function MailUnsubscribePage() {
  const location = useLocation()
  return <MailUnsubscribe search={location.search} brand={<BrandTitle brand={extension.brand} />} appearance={false} />
}
