import { MailLinkRoute } from './MailLinkRoute'

/** Where the link of the "you already have an account" mail leads (decision D36 item 2). */
export default function JoinPage() {
  return <MailLinkRoute page="join" />
}
