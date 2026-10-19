import { api } from '@jabiz/client'
import { CircleCheck, Loader2Icon, TriangleAlert } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription } from '../components/ui/alert'
import { AuthFrame } from './frame'
import { AUTH_NAMESPACE } from './messages'

/** Where the unsubscribe page stands: posting the token, done, a token that is no good, or another failure. */
export type MailUnsubscribeState = 'working' | 'done' | 'invalid' | 'failed'

export interface MailUnsubscribeProps {
  /** The application's name, the page's heading; the page's title when absent. */
  brand?: ReactNode
  /** Offers the appearance as well as the language (default true). */
  appearance?: boolean
  /** The query of the address, when a router keeps its own location; the address bar's when absent. */
  search?: string
}

/** The address bar without the link's token: it is used once, and should not stay in the history or be shared. */
function dropTokenFromAddress() {
  const url = new URL(window.location.href)
  if (!url.searchParams.has('token')) return
  url.searchParams.delete('token')
  window.history.replaceState(window.history.state, '', url.pathname + url.search + url.hash)
}

/**
 * The page the unsubscribe link of a notification mail leads to (docs/design/18-numbering-approvals-tasks.md section
 * 5.6; decision D52 item 3): reads the link's token once, takes it out of the address bar, posts it once to
 * `POST /api/auth/mail/unsubscribe` and says whether the notifications are off, in one live region whose content
 * changes. Outside the sign-in: the signed token is the credential, and it unsubscribes its own user from its own
 * template whatever frontend shows the page (decision D49 item 1). An application mounts it at the path it configures
 * as `jabiz.mail.unsubscribe-paths.<entry>`, under its entry's `app-path`; the administration keeps its own at
 * `/mail/unsubscribe`, for every other notification and the links mailed before.
 */
export function MailUnsubscribe({ brand, appearance, search }: MailUnsubscribeProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  // Read once: the address changes as soon as the token is taken out of it.
  const [token] = useState(() => new URLSearchParams(search ?? window.location.search).get('token'))
  const [state, setState] = useState<MailUnsubscribeState>(token ? 'working' : 'invalid')
  const sent = useRef(false)

  useEffect(() => {
    dropTokenFromAddress()
    // Once, even when React runs effects twice in development; repeating it would change nothing anyway.
    if (!token || sent.current) return
    sent.current = true
    api.POST('/api/auth/mail/unsubscribe', { body: { token } })
      .then(({ response }) => setState(response.ok ? 'done' : response.status === 422 ? 'invalid' : 'failed'))
      .catch(() => setState('failed'))
  }, [token])

  const ok = state === 'done'
  return (
    <AuthFrame brand={brand ?? t('unsubscribe.title')} appearance={appearance} title={t('unsubscribe.title')}
      testId="mail-unsubscribe">
      <div aria-live="polite" aria-atomic="true" data-testid="unsubscribe-state" data-state={state}>
        {state === 'working' ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2Icon aria-hidden className="size-4 animate-spin motion-reduce:animate-none" />
            {t('unsubscribe.working')}
          </p>
        ) : (
          // In the live region above: no role of its own, so that the change is announced once.
          <Alert variant={ok ? 'default' : 'destructive'} role={undefined}>
            {ok ? <CircleCheck aria-hidden className="text-success" /> : <TriangleAlert aria-hidden />}
            <AlertDescription className={ok ? 'text-foreground' : undefined}>{t(`unsubscribe.${state}`)}</AlertDescription>
          </Alert>
        )}
      </div>
    </AuthFrame>
  )
}
