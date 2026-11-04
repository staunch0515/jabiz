import { MailLinkRoute } from './MailLinkRoute'

/** Adding a passkey after confirming by mail (decision D71 item 4): users without a password, in their session. */
export default function PasskeyConfirmPage() {
  return <MailLinkRoute page="passkey-confirm" />
}
