import { Alert, AlertDescription, Button } from '@jabiz/ui'
import { CircleCheck, TriangleAlert } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'

/** The outcome of an account page: what happened, and the way on. */
export function Outcome({ ok, children, testId, next }: { ok: boolean; children: ReactNode; testId: string; next?: ReactNode }) {
  return (
    <>
      <Alert variant={ok ? 'default' : 'destructive'} role={ok ? 'status' : 'alert'} data-testid={testId}>
        {ok ? <CircleCheck aria-hidden className="text-success" /> : <TriangleAlert aria-hidden />}
        <AlertDescription className={ok ? 'text-foreground' : undefined}>{children}</AlertDescription>
      </Alert>
      {next}
    </>
  )
}

/** A link to where the entry's pages are, as a button: a full page load, since it may be another application. */
export function ContinueTo({ path, label }: { path: string; label?: string }) {
  const { t } = useTranslation()
  return (
    <Button asChild size="lg" className="w-full">
      <a href={path} data-testid="auth-continue">
        {label ?? t('auth.continue')}
      </a>
    </Button>
  )
}
