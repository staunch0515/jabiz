import {
  Alert,
  AlertDescription,
  AlertTitle,
  Button,
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  Input,
  Label,
} from '@jabiz/ui'
import { CircleAlert, TriangleAlert } from 'lucide-react'
import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { setStepUpHandler, stepUp } from '../api/client'
import { ApiError } from '../api/problem'

/**
 * Asks for a second factor when an operation requires a recent one (403 MFA_REQUIRED; docs/design/10-security.md
 * section 10). The API client waits for the answer and repeats the request with the new access token; cancelling
 * leaves the original refusal to the caller.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
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
      if (e instanceof ApiError && e.violations.some((v) => v.ruleCode === 'MFA_NOT_ENROLLED')) {
        setNotEnrolled(true)
      } else {
        setError(e instanceof ApiError ? e.display : t('app.error'))
      }
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
              // The description above says why; the alert leads to where it is set up.
              <Alert variant="warning">
                <TriangleAlert aria-hidden />
                <AlertDescription>
                  <Link to="/account/security" className="text-primary underline" onClick={() => finish(false)}>
                    {t('app.security')}
                  </Link>
                </AlertDescription>
              </Alert>
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
