import {
  formatDateTime,
  isProblem,
  listIdentities,
  sitePath,
  startIdentityLink,
  unlinkIdentity,
  type IdentityLinked,
  type IdentityLinkOptions,
  type LinkedIdentity,
} from '@jabiz/client'
import { CircleAlert, KeyRound } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ConfirmDialog } from '../components/confirm-dialog'
import { Spinner } from '../components/spinner'
import { Alert, AlertTitle } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '../components/ui/dialog'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { cn } from '../lib/utils'
import { AuthError, Outcome } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { ProviderMark, useOidcProviders, type OidcProvider } from './OidcButtons'
import { failure, refusal } from './problems'

export interface LinkedIdentitiesProps {
  /** The page to come back to after the round trip through the provider (a path of this site): usually this one. */
  returnTo: string
  /** The providers offered to the frontend's entry when the caller has them; loaded otherwise. */
  providers?: readonly OidcProvider[]
  /** Lists the linked accounts; `listIdentities` of `@jabiz/client` by default. Pass a stable function. */
  list?: () => Promise<LinkedIdentity[]>
  /** Starts linking (leaves the page); `startIdentityLink` by default. */
  start?: (providerId: string, options: IdentityLinkOptions, returnTo: string) => Promise<void>
  /** Removes a link; `unlinkIdentity` by default. */
  unlink?: (id: string) => Promise<void>
  /** What the round trip just finished said (`OidcCallback`'s `onLinked`), shown once above the list. */
  linked?: IdentityLinked | null
  className?: string
}

/**
 * The signed-in user's accounts at identity providers (docs/design/10-security.md section 12; decision D39 item 2;
 * phase 16f): each linked one with its address and when it was linked, removable (after confirming; never the last way
 * to sign in, which the server refuses), and a button to link each provider offered to the frontend's entry that is not
 * linked yet. Linking asks for the current password first unless the session passed a second factor recently (the
 * server says which), then goes through the provider and comes back to `returnTo`. Nothing when no provider is offered
 * and none is linked.
 */
export function LinkedIdentities({
  returnTo,
  providers,
  list = listIdentities,
  start = startIdentityLink,
  unlink = unlinkIdentity,
  linked,
  className,
}: LinkedIdentitiesProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const offered = useOidcProviders(providers)
  const passwordId = useId()
  const [identities, setIdentities] = useState<LinkedIdentity[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  // The provider whose link waits for the current password, and the dialog's state.
  const [asking, setAsking] = useState<OidcProvider | null>(null)
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    list().then(
      (answer) => {
        if (cancelled) return
        setIdentities(answer)
        setError(null)
      },
      (e: unknown) => {
        if (!cancelled) setError(failure(e, t('identities.failed')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [list, version, t])

  const labelOf = (provider: string | undefined) =>
    offered.find((p) => p.id === provider)?.label
      ?? identities?.find((i) => i.provider === provider)?.label
      ?? provider
      ?? ''
  const linkedNotice = linked?.provider
    ? t(linked.status === 'ALREADY_LINKED' ? 'identities.alreadyLinked' : 'identities.linked', {
      provider: labelOf(linked.provider),
    })
    : null

  const items = identities ?? []
  const linkable = offered.filter((p) => !items.some((i) => i.provider === p.id))

  const begin = async (provider: OidcProvider, currentPassword?: string) => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      await start(provider.id!, { currentPassword }, sitePath(returnTo))
    } catch (e) {
      if (isProblem(e, 'REAUTHENTICATION_UNAVAILABLE', 422)) {
        // No password to ask for: a second factor (once set up) or a password set through the reset.
        setAsking(null)
        setError(t('identities.reauthUnavailable'))
      } else if (isProblem(e, 'REAUTHENTICATION_REQUIRED', 422)) {
        setPassword('')
        setPasswordError(null)
        setAsking(provider)
      } else if (currentPassword !== undefined && isProblem(e, 'PASSWORD_INCORRECT', 422)) {
        setPasswordError(refusal(e, t('common.error')))
      } else {
        setAsking(null)
        setError(failure(e, t('identities.failed')))
      }
    } finally {
      setBusy(false)
    }
  }

  const remove = async (identity: LinkedIdentity) => {
    setDone(null)
    setError(null)
    try {
      await unlink(identity.id!)
    } catch (e) {
      setError(failure(e, t('identities.failed')))
      throw e
    }
    setDone(t('identities.unlinked', { provider: identity.label ?? identity.provider }))
    setVersion((v) => v + 1)
  }

  if (identities && items.length === 0 && offered.length === 0) return null

  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="linked-identities">
      <p className="text-muted-foreground text-sm">{t('identities.hint')}</p>
      {linkedNotice && !done && (
        <Outcome ok testId="linked-identities-linked">
          {linkedNotice}
        </Outcome>
      )}
      {done && (
        <Outcome ok testId="linked-identities-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="linked-identities-error" />}
      {!identities && !error && <Spinner />}
      {identities && items.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="linked-identities-none">
          {t('identities.none')}
        </p>
      )}
      {items.length > 0 && (
        <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={t('identities.title')}>
          {items.map((identity) => (
            <li key={identity.id} className="flex flex-wrap items-center gap-3 p-3" data-testid="linked-identity">
              <ProviderMark kind={identity.kind} className="text-muted-foreground size-5 shrink-0" />
              {!identity.kind || identity.kind === 'generic' ? (
                <KeyRound aria-hidden className="text-muted-foreground size-5 shrink-0" />
              ) : null}
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="font-medium">{identity.label ?? identity.provider}</span>
                <dl className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  {identity.email && (
                    <div className="flex min-w-0 gap-1">
                      <dt className="sr-only">{t('identities.email')}</dt>
                      <dd className="truncate">{identity.email}</dd>
                    </div>
                  )}
                  <div className="flex gap-1">
                    <dt>{t('identities.linkedAt')}</dt>
                    <dd>{identity.linkedAt ? formatDateTime(identity.linkedAt) : '-'}</dd>
                  </div>
                </dl>
              </div>
              <ConfirmDialog
                destructive
                title={t('identities.unlinkTitle', { provider: identity.label ?? identity.provider })}
                description={t('identities.unlinkHint')}
                confirmLabel={t('identities.unlink')}
                cancelLabel={t('identities.cancel')}
                onConfirm={() => remove(identity)}
                trigger={
                  <Button variant="outline" size="sm" data-testid="linked-identity-remove"
                    aria-label={t('identities.unlinkNamed', { provider: identity.label ?? identity.provider })}>
                    {t('identities.unlink')}
                  </Button>
                }
              />
            </li>
          ))}
        </ul>
      )}
      {identities && linkable.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {linkable.map((provider) => (
            <Button
              key={provider.id}
              variant="outline"
              disabled={busy}
              data-testid={`link-${provider.id}`}
              onClick={() => void begin(provider)}
            >
              <ProviderMark kind={provider.kind} />
              {t('identities.link', { provider: provider.label })}
            </Button>
          ))}
        </div>
      )}
      <Dialog open={asking !== null} onOpenChange={(next) => !next && !busy && setAsking(null)}>
        <DialogContent className="sm:max-w-md">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (asking && password && !busy) void begin(asking, password)
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('identities.confirmTitle')}</DialogTitle>
              <DialogDescription>{t('identities.confirmHint', { provider: asking?.label ?? '' })}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              {passwordError && (
                <Alert variant="destructive" data-testid="link-password-error">
                  <CircleAlert aria-hidden />
                  <AlertTitle>{passwordError}</AlertTitle>
                </Alert>
              )}
              <Label htmlFor={passwordId}>{t('identities.password')}</Label>
              <Input
                id={passwordId}
                type="password"
                autoFocus
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                aria-invalid={passwordError ? true : undefined}
                data-testid="link-password"
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setAsking(null)} disabled={busy}>
                {t('identities.cancel')}
              </Button>
              <Button type="submit" disabled={!password || busy} aria-busy={busy || undefined}
                data-testid="link-continue">
                {t('identities.continue')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}
