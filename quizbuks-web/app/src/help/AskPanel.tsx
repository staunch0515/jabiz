import { useOptionalAuth } from '@jabiz/client'
import { useTranslation } from 'react-i18next'
import { HELP_THREAD_PARAM } from '../qb/help'
import { HelpChat } from '../qb/HelpChat'
import { HELP } from '../qb/ids'
import { QB } from '../qb/messages'
import { Link, navigate, useLocation } from '../qb/router'
import { askedFrom, helpThreadPath } from './pages'

/**
 * "Ask the AI" at the top of the help page (F-C10c; "My → Help"): a signed-in Taker's conversation with the AI help.
 * The conversation open is in the address (`?thread=`), so that the inbox's link and the back button lead to it. The
 * page's name sent is the page the Taker came from (`pages.ts`); the answering pages have none and no help of their
 * own.
 */
export function AskPanel() {
  const { t } = useTranslation(QB)
  const auth = useOptionalAuth()
  const { search } = useLocation()
  if (!auth?.signedIn) {
    return (
      <p className="text-muted-foreground rounded-md border p-3 text-sm" data-testid="help-ai-signed-out">
        <Link to={`/login?next=${encodeURIComponent('/help')}`} className="text-primary underline">
          {t('helpAi.signedOut')}</Link>
      </p>
    )
  }
  if (!auth.permissions.has(HELP.use)) return null
  const threadId = new URLSearchParams(search).get(HELP_THREAD_PARAM)
  return (
    <div className="rounded-md border p-4" data-testid="help-ask">
      <HelpChat entry="app" page={askedFrom()} threadId={threadId}
        onThreadChange={(next) => navigate(next ? helpThreadPath(next) : '/help', { replace: true })} />
    </div>
  )
}
