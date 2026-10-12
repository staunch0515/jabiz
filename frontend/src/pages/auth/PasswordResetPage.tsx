import { MailLinkRoute } from './MailLinkRoute'

/** Resetting a forgotten password (decision D36 item 5): asking for the link, or with its token setting the new one. */
export default function PasswordResetPage() {
  return <MailLinkRoute page="password-reset" />
}
