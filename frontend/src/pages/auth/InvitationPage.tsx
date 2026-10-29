import { MailLinkRoute } from './MailLinkRoute'

/** Accepting an administrator's invitation (decision D63): the invited person sets their own password. */
export default function InvitationPage() {
  return <MailLinkRoute page="invitation" />
}
