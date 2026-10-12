import { isProblem, setStepUpHandler, stepUp } from '@jabiz/client'
import { CircleAlert, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Alert, AlertDescription, AlertTitle } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '../components/ui/dialog'
import { Input } from '../components/ui/input'
import { Label } from '../components/ui/label'
import { AuthLink, type AuthNavigation } from './frame'
import { AUTH_NAMESPACE } from './messages'
import { refusal } from './problems'

export interface StepUpProviderProps extends AuthNavigation {
  children: ReactNode
  /** The page where two-step verification is set up, as a path of this frontend; no link when absent. */
  securityPath?: string
  /** The name of that page in the link; "Security" by default. */
  securityLabel?: string
}

/**
 * Asks for a second factor when an operation requires a recent one (403 MFA_REQUIRED; docs/design/10-security.md
 * section 10). The API client waits for the answer and repeats the request with the new access token; concurrent
 * requests share one prompt, and cancelling leaves the original refusal to the caller. A user without two-step
 * verification is pointed to where it is set up.
 */
export function StepUpProvider({ children, securityPath, securityLabel, onNavigate, hrefOf }: StepUpProviderProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const codeId = useId()
  const [open, setOpen] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notEnrolled, setNotEnrolled] = useState(false)
  const [busy, setBusy] = useState(false)
  const pending = useRef<((passed: boolean) => void) | null>(null)

  useEffect(() => {
    setStepUpHandler(
      () =>
        new Promise<boolean>((resolve) => {
          pending.current = resolve
          setCode('')
          setError(null)
          setNotEnrolled(false)
          setOpen(true)
        }),
    )
    return () => setStepUpHandler(null)
  }, [])

  const finish = (passed: boolean) => {
    setOpen(false)
    pending.current?.(passed)
    pending.current = null
  }

  const submit = async () => {
    if (!code.trim() || notEnrolled || busy) return
    setBusy(true)
    setError(null)
    try {
      await stepUp(code.trim())
      finish(true)
    } catch (e) {
      if (isProblem(e, 'MFA_NOT_ENROLLED')) setNotEnrolled(true)
      else setError(refusal(e, t('common.error')))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      {children}
      <Dialog open={open} onOpenChange={(next) => !next && !busy && finish(false)}>
        <DialogContent className="sm:max-w-md">
          <form
            className="grid gap-4"
            onSubmit={(event) => {
              event.preventDefault()
              void submit()
            }}
          >
            <DialogHeader>
              <DialogTitle>{t('mfa.stepUpTitle')}</DialogTitle>
              <DialogDescription>{notEnrolled ? t('mfa.stepUpNotEnrolled') : t('mfa.stepUpHint')}</DialogDescription>
            </DialogHeader>
            {notEnrolled ? (
              securityPath && (
                // The description above says why; the alert leads to where it is set up.
                <Alert variant="warning">
                  <TriangleAlert aria-hidden />
                  <AlertDescription>
                    <AuthLink
                      to={securityPath}
                      onNavigate={onNavigate}
                      hrefOf={hrefOf}
                      className="text-primary underline"
                      onClick={() => finish(false)}
                    >
                      {securityLabel ?? t('common.security')}
                    </AuthLink>
                  </AlertDescription>
                </Alert>
              )
            ) : (
              <div className="grid gap-2">
                {error && (
                  <Alert variant="destructive" data-testid="step-up-error">
                    <CircleAlert aria-hidden />
                    <AlertTitle>{error}</AlertTitle>
                  </Alert>
                )}
                <Label htmlFor={codeId}>{t('login.code')}</Label>
                <Input
                  id={codeId}
                  autoFocus
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  aria-invalid={error ? true : undefined}
                  data-testid="step-up-code"
                />
              </div>
            )}
            <DialogFooter>
              <Button variant="outline" onClick={() => finish(false)} disabled={busy}>
                {t('mfa.cancel')}
              </Button>
              <Button
                type="submit"
                id="step-up-submit"
                disabled={!code.trim() || notEnrolled || busy}
                aria-busy={busy || undefined}
              >
                {t('login.verify')}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>
    </>
  )
}
