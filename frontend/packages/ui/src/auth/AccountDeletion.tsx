import {
  cancelAccountDeletion,
  displayLocale,
  getAccountDeletion,
  isProblem,
  requestAccountDeletion,
  unacknowledgedNotices,
  type AccountDeletionNotice,
  type AccountDeletionRequest,
  type AccountDeletionRequested,
  type AccountDeletionStatus,
} from '@jabiz/client'
import { CalendarClock, CircleAlert } from 'lucide-react'
import { useEffect, useId, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Alert, AlertDescription, AlertTitle } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Checkbox } from '../components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '../components/ui/dialog'
import { Label } from '../components/ui/label'
import { cn } from '../lib/utils'
import { AuthError, Outcome } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { PasswordInput } from './PasswordInput'
import { failure, refusal } from './problems'

export interface AccountDeletionProps {
  /** Reads the state of the deletion; `getAccountDeletion` of `@jabiz/client` by default. Pass a stable function. */
  load?: () => Promise<AccountDeletionStatus>
  /** Asks for the deletion; `requestAccountDeletion` by default. */
  request?: (request: AccountDeletionRequest) => Promise<AccountDeletionRequested>
  /** Withdraws a pending request; `cancelAccountDeletion` by default. */
  cancel?: () => Promise<unknown>
  /** The request was made: when the account is deleted. */
  onRequested?: (requested: AccountDeletionRequested) => void
  className?: string
}

/**
 * The signed-in user's own account deletion (docs/design/10-security.md section 17; decision D62), for the account
 * page. A pending request says when the account is deleted and can be withdrawn. Otherwise, where the user's entry
 * lets them, it explains the waiting period, lists the notes to confirm (each a required checkbox) and asks, after a
 * confirmation, for the deletion; the current password is asked for only when the server wants it (no recent second
 * factor). Where accounts cannot be deleted, a short note.
 */
export function AccountDeletion({
  load = getAccountDeletion,
  request = requestAccountDeletion,
  cancel = cancelAccountDeletion,
  onRequested,
  className,
}: AccountDeletionProps) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const passwordId = useId()
  const [status, setStatus] = useState<AccountDeletionStatus | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [version, setVersion] = useState(0)
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState<string | null>(null)
  const [acknowledged, setAcknowledged] = useState<string[]>([])
  const [noticeError, setNoticeError] = useState(false)
  // The confirmation, with the current password once the server asked for it.
  const [open, setOpen] = useState(false)
  const [needPassword, setNeedPassword] = useState(false)
  const [password, setPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    load().then(
      (answer) => {
        if (cancelled) return
        setStatus(answer)
        setLoadError(null)
      },
      (e: unknown) => {
        if (!cancelled) setLoadError(failure(e, t('accountDeletion.failed')))
      },
    )
    return () => {
      cancelled = true
    }
  }, [load, version, t])

  const reload = () => setVersion((v) => v + 1)
  const notices = status?.notices ?? []
  const dateOf = (value: string | undefined) => formatDueDate(value, i18n.language)

  const begin = () => {
    setError(null)
    setDone(null)
    if (notices.some((n) => !acknowledged.includes(n.code!))) {
      setNoticeError(true)
      return
    }
    setNoticeError(false)
    setNeedPassword(false)
    setPassword('')
    setPasswordError(null)
    setOpen(true)
  }

  const send = async (currentPassword?: string) => {
    setBusy(true)
    setError(null)
    try {
      const answer = await request({ currentPassword, acknowledgedNotices: acknowledged })
      setOpen(false)
      setStatus({ available: true, pending: { requestId: answer.requestId, dueTime: answer.dueTime }, notices: [] })
      onRequested?.(answer)
    } catch (e) {
      if (isProblem(e, 'REAUTHENTICATION_REQUIRED', 422)) {
        setNeedPassword(true)
        setPassword('')
        setPasswordError(null)
        return
      }
      if (currentPassword !== undefined && isProblem(e, 'PASSWORD_INCORRECT', 422)) {
        setPasswordError(refusal(e, t('common.error')))
        return
      }
      setOpen(false)
      const missing = unacknowledgedNotices(e)
      if (missing.length > 0) {
        // The notes changed since they were read: show the new ones unticked, and read them all again.
        setStatus((s) => ({ ...s, notices: mergeNotices(s?.notices ?? [], missing) }))
        setAcknowledged((a) => a.filter((code) => !missing.some((m) => m.code === code)))
        setError(t('accountDeletion.noticesChanged'))
        reload()
      } else if (isProblem(e, 'REAUTHENTICATION_UNAVAILABLE', 422)) {
        setError(t('accountDeletion.reauthUnavailable'))
      } else if (isProblem(e, 'ACCOUNT_DELETION_PENDING', 422)) {
        setError(t('accountDeletion.alreadyPending'))
        reload()
      } else if (isProblem(e, 'ACCOUNT_DELETION_PROTECTED', 422)) {
        setError(t('accountDeletion.protected'))
      } else if (isProblem(e, 'ACCOUNT_DELETION_UNAVAILABLE', 422)) {
        setError(t('accountDeletion.unavailable'))
        reload()
      } else {
        setError(failure(e, t('accountDeletion.failed')))
      }
    } finally {
      setBusy(false)
    }
  }

  const withdraw = async () => {
    setBusy(true)
    setError(null)
    setDone(null)
    try {
      await cancel()
      setDone(t('accountDeletion.withdrawn'))
    } catch (e) {
      if (isProblem(e, 'ACCOUNT_DELETION_NONE', 422)) setError(t('accountDeletion.none'))
      else setError(failure(e, t('accountDeletion.failed')))
    } finally {
      setBusy(false)
      reload()
    }
  }

  const pending = status?.pending
  return (
    <div className={cn('flex flex-col gap-4', className)} data-testid="account-deletion">
      {done && (
        <Outcome ok testId="account-deletion-state">
          {done}
        </Outcome>
      )}
      {error && <AuthError message={error} testId="account-deletion-error" />}
      {loadError && <AuthError message={loadError} testId="account-deletion-error" />}
      {!status && !loadError && <Spinner />}
      {status && pending && (
        <>
          <Alert role="status" data-testid="account-deletion-pending">
            <CalendarClock aria-hidden />
            <AlertDescription className="text-foreground">
              {t('accountDeletion.pending', { date: dateOf(pending.dueTime) })}
            </AlertDescription>
          </Alert>
          <Button variant="outline" className="self-start" disabled={busy} aria-busy={busy || undefined}
            data-testid="account-deletion-withdraw" onClick={() => void withdraw()}>
            {t('accountDeletion.withdraw')}
          </Button>
        </>
      )}
      {status && !pending && !status.available && (
        <p className="text-muted-foreground text-sm" data-testid="account-deletion-unavailable">
          {t('accountDeletion.unavailable')}
        </p>
      )}
      {status && !pending && status.available && (
        <>
          <p className="text-muted-foreground text-sm">{t('accountDeletion.hint')}</p>
          <NoticeChecklist
            notices={notices}
            acknowledged={acknowledged}
            invalid={noticeError}
            onChange={(next) => {
              setAcknowledged(next)
              if (notices.every((n) => next.includes(n.code!))) setNoticeError(false)
            }}
          />
          <Button variant="destructive" className="self-start" disabled={busy} data-testid="account-deletion-start"
            onClick={begin}>
            {t('accountDeletion.start')}
          </Button>
        </>
      )}
      <Dialog open={open} onOpenChange={(next) => !next && !busy && setOpen(false)}>
        <DialogContent className="sm:max-w-md">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              if (busy || (needPassword && !password)) return
              void send(needPassword ? password : undefined)
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('accountDeletion.confirmTitle')}</DialogTitle>
              <DialogDescription>{t('accountDeletion.confirmHint')}</DialogDescription>
            </DialogHeader>
            {needPassword && (
              <div className="grid gap-2">
                <p className="text-sm" data-testid="account-deletion-password-hint">
                  {t('accountDeletion.passwordHint')}
                </p>
                {passwordError && (
                  <Alert variant="destructive" data-testid="account-deletion-password-error">
                    <CircleAlert aria-hidden />
                    <AlertTitle>{passwordError}</AlertTitle>
                  </Alert>
                )}
                <Label htmlFor={passwordId}>{t('accountDeletion.password')}</Label>
                <PasswordInput
                  id={passwordId}
                  autoFocus
                  autoComplete="current-password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  aria-invalid={passwordError ? true : undefined}
                  data-testid="account-deletion-password"
                />
              </div>
            )}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setOpen(false)} disabled={busy}>
                {t('accountDeletion.cancel')}
              </Button>
              <Button type="submit" variant="destructive" disabled={busy || (needPassword && !password)}
                aria-busy={busy || undefined} data-testid="account-deletion-confirm">
                {t('accountDeletion.start')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/**
 * The notes to confirm before an account is deleted (`erasure.notice.<code>`, their text from the server), each a
 * required checkbox. Nothing when there are none.
 */
export function NoticeChecklist({ notices, acknowledged, invalid = false, onChange }: {
  notices: readonly AccountDeletionNotice[]
  acknowledged: readonly string[]
  /** Some are not ticked although they had to be: said under the list, and on each that is not. */
  invalid?: boolean
  onChange(acknowledged: string[]): void
}) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const baseId = useId()
  if (notices.length === 0) return null
  const errorId = `${baseId}-error`
  return (
    <fieldset className="flex flex-col gap-3" data-testid="account-deletion-notices"
      aria-describedby={invalid ? errorId : undefined}>
      <legend className="mb-2 text-sm font-medium">{t('accountDeletion.notices')}</legend>
      {notices.map((notice, index) => {
        const id = `${baseId}-${index}`
        const ticked = acknowledged.includes(notice.code!)
        return (
          <div key={notice.code} className="flex items-start gap-2">
            <Checkbox
              id={id}
              className="mt-0.5"
              checked={ticked}
              required
              aria-invalid={invalid && !ticked ? true : undefined}
              data-testid="account-deletion-notice"
              onCheckedChange={(checked) => onChange(checked === true
                ? [...acknowledged.filter((c) => c !== notice.code), notice.code!]
                : acknowledged.filter((c) => c !== notice.code))}
            />
            <Label htmlFor={id} className="text-sm leading-snug font-normal">
              {notice.message}
            </Label>
          </div>
        )
      })}
      {invalid && (
        <p id={errorId} className="text-destructive text-sm" data-testid="account-deletion-notices-error">
          {t('accountDeletion.noticesRequired')}
        </p>
      )}
    </fieldset>
  )
}

/** The notes shown, with the server's text for those it named, and those it named that were not shown at the end. */
export function mergeNotices(shown: readonly AccountDeletionNotice[],
  named: readonly AccountDeletionNotice[]): AccountDeletionNotice[] {
  return [
    ...shown.map((n) => named.find((m) => m.code === n.code) ?? n),
    ...named.filter((m) => !shown.some((n) => n.code === m.code)),
  ]
}

/** The day the account is deleted, in the interface language (or the application's region): "November 11, 2026". */
export function formatDueDate(value: string | undefined, language: string): string {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(displayLocale(language), { dateStyle: 'long' }).format(date)
}
