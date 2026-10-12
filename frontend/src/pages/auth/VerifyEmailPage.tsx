import { MailLinkRoute } from './MailLinkRoute'

/** Where the link of a verification mail leads (docs/design/10-security.md section 15.7; decision D36 item 3). */
export default function VerifyEmailPage() {
  return <MailLinkRoute page="verify-email" />
}
