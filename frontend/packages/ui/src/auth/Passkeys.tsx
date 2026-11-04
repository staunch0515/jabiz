import {
  PasskeyCancelledError,
  addPasskey,
  entryInfo,
  formatDateTime,
  isProblem,
  useOptionalAuth,
  listPasskeys,
  passkeySupport,
  removePasskey,
  renamePasskey,
  requestPasskeyConfirmation,
  type Passkey,
  type PasskeyProof,
} from '@jabiz/client'
import { CircleAlert, Fingerprint, Info, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { ConfirmDialog } from '../components/confirm-dialog'
import { Spinner } from '../components/spinner'
import { Alert, AlertDescription, AlertTitle } from '../components/ui/alert'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '../components/ui/dialog'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { cn } from '../lib/utils'
import { AuthError, AuthFrame, ContinueTo, Outcome } from './frame'
import type { MailLinkInfo, MailLinkPageBase } from './MailLinkPage'
import { AUTH_NAMESPACE } from './messages'
import { PasswordInput } from './PasswordInput'
import { failure, invalidLink, refusal } from './problems'

/** A name for a new passkey: the kind of device it is made on (the person can rename it). */
export function passkeyDeviceName(userAgent: string = typeof navigator === 'undefined' ? '' : navigator.userAgent): string {
  if (/iPhone/.test(userAgent)) return 'iPhone'
  if (/iPad/.test(userAgent)) return 'iPad'
  if (/HarmonyOS|OpenHarmony/.test(userAgent)) return 'HarmonyOS'
  if (/Android/.test(userAgent)) return 'Android'
  if (/Macintosh/.test(userAgent)) return 'Mac'
  if (/Windows/.test(userAgent)) return 'Windows'
  if (/CrOS/.test(userAgent)) return 'Chromebook'
  if (/Linux/.test(userAgent)) return 'Linux'
  return 'Passkey'
}

export interface PasskeysProps {
  /** Lists the passkeys; `listPasskeys` of `@jabiz/client` by default. Pass a stable function. */
  list?: () => Promise<Passkey[]>
  /** Whether the entry takes passkeys; asked (`entryInfo().passkeys`) when absent. */
  offered?: boolean
  className?: string
}

/**
 * The signed-in user's passkeys (decision D71; docs/design/10-security.md section 15.16): each with when it was added
 * and last used and whether it is synced, renamed and removed here; a button to add one, which asks for the current
 * password unless the session passed a second factor recently, and for a user without a password mails a link to
 * confirm first (opened on the same device: `MailLinkPage`'s `passkey-confirm`). Nothing when the frontend's entry
 * does not take passkeys; in another app's browser it says to open the page in the browser.
 */
export function Passkeys({ list = listPasskeys, offered, className }: PasskeysProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const passwordId = useId()
  const nameId = useId()
  const entryOffers = useEntryPasskeys(offered)
  const support = passkeySupport()
  const [passkeys, setPasskeys] = useState<Passkey[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [busy, setBusy] = useState(false)
  const [asking, setAsking] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [mail, setMail] = useState<'offer' | 'sent' | null>(null)
  const [renaming, setRenaming] = useState<Passkey | null>(null)
  const [newName, setNewName] = useState('')

  useEffect(() => {
    if (!entryOffers) return undefined
    let cancelled = false
    list().then(
      (answer) => {
        if (cancelled) return
        setPasskeys(answer)
        setError(null)
      },
      (e: unknown) => {
        if (!cancelled) setError(failure(e, t('passkeys.failed')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [list, version, entryOffers, t])

  if (!entryOffers) return null

  const add = async (proof: PasskeyProof = {}) => {
    setBusy(true)
    setError(null)
    setDone(null)
    const name = passkeyDeviceName()
    try {
      await addPasskey(proof, name)
      setAsking(false)
      setDone(t('passkeys.added', { name }))
      setVersion((v) => v + 1)
    } catch (e) {
      if (e instanceof PasskeyCancelledError) {
        // The person closed the system's dialog: nothing to say.
      } else if (isProblem(e, 'REAUTHENTICATION_UNAVAILABLE', 422)) {
        setAsking(false)
        setMail('offer')
      } else if (isProblem(e, 'REAUTHENTICATION_REQUIRED', 422)) {
        setPassword('')
        setPasswordError(null)
        setAsking(true)
      } else if (proof.currentPassword !== undefined && isProblem(e, 'PASSWORD_INCORRECT', 422)) {
        setPasswordError(refusal(e, t('common.error')))
      } else {
        setAsking(false)
        setError(refusal(e, t('passkeys.failed')))
      }
    } finally {
      setBusy(false)
    }
  }

  const sendMail = async () => {
    setBusy(true)
    setError(null)
    try {
      await requestPasskeyConfirmation()
      setMail('sent')
    } catch (e) {
      setError(refusal(e, t('passkeys.failed')))
    } finally {
      setBusy(false)
    }
  }

  const remove = async (passkey: Passkey) => {
    setDone(null)
    setError(null)
    try {
      await removePasskey(passkey.id!)
    } catch (e) {
      setError(failure(e, t('passkeys.failed')))
      throw e
    }
    setDone(t('passkeys.removed', { name: passkey.name }))
    setVersion((v) => v + 1)
  }

  const rename = async () => {
    if (!renaming || !newName.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      await renamePasskey(renaming.id!, newName.trim())
      setRenaming(null)
      setDone(t('passkeys.renamed'))
      setVersion((v) => v + 1)
    } catch (e) {
      setRenaming(null)
      setError(failure(e, t('passkeys.failed')))
    } finally {
      setBusy(false)
    }
  }

  const items = passkeys ?? []
  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="passkeys">
      <p className="text-muted-foreground text-sm">{t('passkeys.hint')}</p>
      {done && (
        <Outcome ok testId="passkeys-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="passkeys-error" />}
      {!passkeys && !error && <Spinner />}
      {passkeys && items.length === 0 && (
        <p className="text-muted-foreground text-sm" data-testid="passkeys-none">
          {t('passkeys.none')}
        </p>
      )}
      {items.length > 0 && (
        <ul className="divide-border flex flex-col divide-y rounded-md border" aria-label={t('passkeys.title')}>
          {items.map((passkey) => (
            <li key={passkey.id} className="flex flex-wrap items-center gap-3 p-3" data-testid="passkey">
              <Fingerprint aria-hidden className="text-muted-foreground size-5 shrink-0" />
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <span className="flex flex-wrap items-center gap-2 font-medium">
                  <span className="truncate" data-testid="passkey-name">{passkey.name}</span>
                  {passkey.backedUp && <Badge variant="secondary">{t('passkeys.synced')}</Badge>}
                </span>
                <dl className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
                  <div className="flex gap-1">
                    <dt>{t('passkeys.createdAt')}</dt>
                    <dd>{passkey.createdAt ? formatDateTime(passkey.createdAt) : '-'}</dd>
                  </div>
                  <div className="flex gap-1">
                    <dt>{t('passkeys.lastUsedAt')}</dt>
                    <dd>{passkey.lastUsedAt ? formatDateTime(passkey.lastUsedAt) : t('passkeys.never')}</dd>
                  </div>
                </dl>
              </div>
              <Button variant="outline" size="sm" data-testid="passkey-rename"
                aria-label={t('passkeys.renameNamed', { name: passkey.name })}
                onClick={() => {
                  setNewName(passkey.name ?? '')
                  setRenaming(passkey)
                }}>
                {t('passkeys.rename')}
              </Button>
              <ConfirmDialog
                destructive
                title={t('passkeys.removeTitle', { name: passkey.name })}
                description={t('passkeys.removeHint')}
                confirmLabel={t('passkeys.remove')}
                cancelLabel={t('passkeys.cancel')}
                onConfirm={() => remove(passkey)}
                trigger={
                  <Button variant="outline" size="sm" data-testid="passkey-remove"
                    aria-label={t('passkeys.removeNamed', { name: passkey.name })}>
                    {t('passkeys.remove')}
                  </Button>
                }
              />
            </li>
          ))}
        </ul>
      )}
      {support === 'available' && passkeys && mail === null && (
        <Button variant="outline" className="self-start" disabled={busy} aria-busy={busy || undefined}
          data-testid="passkey-add" onClick={() => void add()}>
          <Fingerprint aria-hidden />
          {t('passkeys.add')}
        </Button>
      )}
      {support !== 'available' && (
        <Alert variant={support === 'in-app-browser' ? 'warning' : 'default'} data-testid="passkeys-unavailable">
          {support === 'in-app-browser' ? <TriangleAlert aria-hidden /> : <Info aria-hidden />}
          <AlertDescription className={support === 'in-app-browser' ? 'text-current' : undefined}>
            {t(support === 'in-app-browser' ? 'passkeys.inApp' : 'passkeys.unsupported')}
          </AlertDescription>
        </Alert>
      )}
      {mail === 'offer' && (
        <div className="flex flex-col gap-2" data-testid="passkey-mail">
          <p className="text-sm">{t('passkeys.mailHint')}</p>
          <Button variant="outline" className="self-start" disabled={busy} aria-busy={busy || undefined}
            data-testid="passkey-send-mail" onClick={() => void sendMail()}>
            {t('passkeys.sendMail')}
          </Button>
        </div>
      )}
      {mail === 'sent' && (
        <Outcome ok testId="passkey-mail-sent">
          {t('passkeys.mailSent')}
        </Outcome>
      )}
      <Dialog open={asking} onOpenChange={(next) => !next && !busy && setAsking(false)}>
        <DialogContent className="sm:max-w-md">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (password && !busy) void add({ currentPassword: password })
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('passkeys.confirmTitle')}</DialogTitle>
              <DialogDescription>{t('passkeys.confirmHint')}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              {passwordError && (
                <Alert variant="destructive" data-testid="passkey-password-error">
                  <CircleAlert aria-hidden />
                  <AlertTitle>{passwordError}</AlertTitle>
                </Alert>
              )}
              <Label htmlFor={passwordId}>{t('passkeys.password')}</Label>
              <PasswordInput
                id={passwordId}
                autoFocus
                autoComplete="current-password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                aria-invalid={passwordError ? true : undefined}
                data-testid="passkey-password"
              />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setAsking(false)} disabled={busy}>
                {t('passkeys.cancel')}
              </Button>
              <Button type="submit" disabled={!password || busy} aria-busy={busy || undefined}
                data-testid="passkey-continue">
                {t('passkeys.continue')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
      <Dialog open={renaming !== null} onOpenChange={(next) => !next && !busy && setRenaming(null)}>
        <DialogContent className="sm:max-w-md">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              void rename()
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('passkeys.renameTitle')}</DialogTitle>
              <DialogDescription className="sr-only">{renaming?.name}</DialogDescription>
            </DialogHeader>
            <div className="grid gap-2">
              <Label htmlFor={nameId}>{t('passkeys.name')}</Label>
              <Input id={nameId} autoFocus maxLength={100} value={newName} data-testid="passkey-new-name"
                onChange={(e) => setNewName(e.target.value)} />
            </div>
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setRenaming(null)} disabled={busy}>
                {t('passkeys.cancel')}
              </Button>
              <Button type="submit" disabled={!newName.trim() || busy} aria-busy={busy || undefined}
                data-testid="passkey-save">
                {t('passkeys.save')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/** Whether the frontend's entry takes passkeys: as given, else asked once (false until known or when it fails). */
export function useEntryPasskeys(given?: boolean): boolean {
  const [asked, setAsked] = useState(false)
  useEffect(() => {
    if (given !== undefined) return undefined
    let live = true
    entryInfo()
      .then((info) => {
        if (live) setAsked(!!info.passkeys)
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [given])
  return given ?? asked
}

type ConfirmState = 'ready' | 'done' | 'invalid' | 'failed'

/**
 * Where the confirmation mail of a user without a password leads (`passkey-confirm`, decision D71 item 4): in the
 * user's signed-in session on this device, a click adds a passkey of this device with the mail's token (the system's
 * dialog needs the click). Signed out, it says to sign in first; the link stays good until used.
 */
export function PasskeyConfirmPanel({ link, brand, appearance }: MailLinkPageBase & { link: MailLinkInfo }) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const auth = useOptionalAuth()
  const support = passkeySupport()
  const [state, setState] = useState<ConfirmState>(link.token ? 'ready' : 'invalid')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [name, setName] = useState('')

  const confirm = async () => {
    setBusy(true)
    setError(null)
    const device = passkeyDeviceName()
    try {
      await addPasskey({ passkeyToken: link.token! }, device)
      setName(device)
      setState('done')
    } catch (e) {
      if (e instanceof PasskeyCancelledError) {
        // Closed the system's dialog: the token was used up proving the user, so a new mail is needed.
        setState('invalid')
      } else if (invalidLink(e)) {
        setState('invalid')
      } else {
        setError(refusal(e, t('account.failed')))
        setState('failed')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <AuthFrame brand={brand} appearance={appearance} title={t('passkeys.confirmPageTitle')} testId="passkey-confirm">
      {state === 'ready' && support !== 'available' && (
        <Alert variant="warning" data-testid="passkey-confirm-state" data-state="unavailable">
          <TriangleAlert aria-hidden />
          <AlertDescription className="text-current">
            {t(support === 'in-app-browser' ? 'passkeys.inApp' : 'passkeys.unsupported')}
          </AlertDescription>
        </Alert>
      )}
      {state === 'ready' && support === 'available' && auth && !auth.ready && <Spinner />}
      {state === 'ready' && support === 'available' && (!auth || auth.ready) && !auth?.signedIn && (
        <Outcome ok={false} testId="passkey-confirm-state">
          <span data-state="signed-out">{t('passkeys.signInFirst')}</span>
        </Outcome>
      )}
      {state === 'ready' && support === 'available' && auth?.ready && auth.signedIn && (
        <>
          <p className="text-sm" data-testid="passkey-confirm-state" data-state="ready">
            {t('passkeys.confirmPageHint')}
          </p>
          <Button size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}
            data-testid="passkey-confirm-start" onClick={() => void confirm()}>
            <Fingerprint aria-hidden />
            {t('passkeys.add')}
          </Button>
        </>
      )}
      {state === 'done' && (
        <Outcome ok testId="passkey-confirm-state" next={<ContinueTo path={link.appPath} />}>
          <span data-state="done">{t('passkeys.added', { name })}</span>
        </Outcome>
      )}
      {state === 'failed' && (
        <Outcome ok={false} testId="passkey-confirm-state">
          <span data-state="failed">{error}</span>
        </Outcome>
      )}
      {state === 'invalid' && (
        <Outcome ok={false} testId="passkey-confirm-state">
          <span data-state="invalid">{t('account.invalidLink')}</span>
        </Outcome>
      )}
    </AuthFrame>
  )
}
