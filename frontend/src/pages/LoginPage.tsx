import {
  Alert,
  AlertDescription,
  Button,
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
  cn,
  Input,
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
  Separator,
  Spinner,
  UI_SCOPE,
  useForm,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { CircleCheck, Info, KeyRound, LockKeyhole, TriangleAlert, User, type LucideIcon } from 'lucide-react'
import { useId, useState, type ComponentProps, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { api, unwrap } from '../api/client'
import { ApiError } from '../api/problem'
import { lastUserName, useAuth, type SignInStep } from '../auth/AuthContext'
import { startProviderSignIn } from '../auth/oidc'
import MfaEnrollment from '../components/MfaEnrollment'
import { extension } from '../extension'
import { homePath } from '../extension/registry'
import { changeLanguage, languages, type Language } from '../i18n'
import { LANGUAGE_NAMES } from '../i18n/languages'

type Step =
  | { kind: 'password'; notice?: 'enrolled' }
  | { kind: 'code'; challenge: string }
  | { kind: 'enroll'; challenge: string }

/** A text field of the sign-in form: an icon, the placeholder as its name, and its error under it. */
function SignInField({
  icon: Icon,
  error,
  ...input
}: { icon: LucideIcon; error?: string } & ComponentProps<'input'>) {
  const errorId = useId()
  return (
    <div className="flex flex-col gap-1.5">
      <div className="relative">
        <Icon aria-hidden className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
        <Input
          {...input}
          aria-label={input.placeholder}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          className="h-10 pl-9"
        />
      </div>
      {error && (
        <p id={errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  )
}

/** The frame of every step: the application's name, the step's title and the language choice. */
function SignInFrame({ subtitle, children, testId }: { subtitle: string; children: ReactNode; testId?: string }) {
  const { t, i18n } = useTranslation()
  return (
    <main className={cn(UI_SCOPE, 'bg-background text-foreground flex min-h-screen flex-col items-center px-4 pt-20')}>
      <Card className="w-full max-w-sm" data-testid={testId}>
        <CardHeader className="text-center">
          <CardTitle>
            <h1 className="text-2xl font-semibold tracking-tight">{t('app.title')}</h1>
          </CardTitle>
          <CardDescription>{subtitle}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">{children}</CardContent>
      </Card>
      {languages.length > 1 && (
        <div className="mt-4">
          <Select value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
            <SelectTrigger size="sm" aria-label={t('app.language')}>
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {languages.map((lang) => (
                <SelectItem key={lang} value={lang} lang={lang}>
                  {LANGUAGE_NAMES[lang]}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
      )}
    </main>
  )
}

function SubmitButton({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <Button type="submit" size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}>
      {busy && <Spinner className="text-current" />}
      {children}
    </Button>
  )
}

/** The second step: a code from the authenticator, or a recovery code. */
function CodeStep({ onVerify, onBack, error }: { onVerify(code: string): Promise<void>; onBack(): void; error: string | null }) {
  const { t } = useTranslation()
  const form = useForm<{ code: string }>({ defaultValues: { code: '' } })
  return (
    <SignInFrame subtitle={t('login.codeTitle')}>
      <Alert role="note">
        <Info aria-hidden />
        <AlertDescription>{t('login.codeHint')}</AlertDescription>
      </Alert>
      {error && (
        <Alert variant="destructive" data-testid="login-error">
          <TriangleAlert aria-hidden />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit(({ code }) => onVerify(code.trim()))}
      >
        <SignInField
          icon={KeyRound}
          placeholder={t('login.code')}
          autoComplete="one-time-code"
          inputMode="numeric"
          autoFocus
          error={form.formState.errors.code?.message}
          {...form.register('code', { validate: (v) => v.trim() !== '' || t('login.codeRequired') })}
        />
        <SubmitButton busy={form.formState.isSubmitting}>{t('login.verify')}</SubmitButton>
      </form>
      <Button variant="link" onClick={onBack}>
        {t('login.back')}
      </Button>
    </SignInFrame>
  )
}

/**
 * Signing in (docs/design/10-security.md sections 4 and 9): the password, then — for users with two-step
 * verification — a code, or the enrolment a role requires before the user signs in again.
 */
export default function LoginPage() {
  const { t, i18n } = useTranslation()
  const { signIn, verify, signedIn, ready } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const [error, setError] = useState<string | null>(null)
  const state = location.state as { from?: string; idle?: boolean; step?: SignInStep } | null
  // Back from an identity provider with a second step to take (docs/design/10-security.md section 12).
  const [step, setStep] = useState<Step>(() => {
    const pending = state?.step
    if (pending?.status === 'MFA_REQUIRED') return { kind: 'code', challenge: pending.challenge }
    if (pending?.status === 'MFA_ENROLLMENT_REQUIRED') return { kind: 'enroll', challenge: pending.challenge }
    return { kind: 'password' }
  })
  // Without a page to return to, the application's home (12 section 9), as the root path leads there too.
  const from = state?.from ?? homePath(extension)
  const providers = useQuery({
    queryKey: ['auth', 'oidc', 'providers', i18n.language],
    queryFn: () => unwrap(api.GET('/api/auth/oidc/providers')),
    staleTime: Infinity,
  })
  const form = useForm<{ userName: string; password: string }>({
    defaultValues: { userName: (state?.idle ? lastUserName() : undefined) ?? '', password: '' },
  })

  if (ready && signedIn) return <Navigate to={from} replace />

  const failed = (e: unknown) => setError(e instanceof ApiError ? e.display : t('app.error'))

  if (step.kind === 'enroll') {
    const challenge = step.challenge
    return (
      <SignInFrame subtitle={t('login.enrollTitle')} testId="mfa-enroll">
        <Alert role="note">
          <Info aria-hidden />
          <AlertDescription>{t('login.enrollHint')}</AlertDescription>
        </Alert>
        <MfaEnrollment
          begin={async () => {
            const answer = await unwrap(api.POST('/api/auth/challenge/enroll', { body: { challenge } }))
            return { secret: answer.secret!, otpauthUri: answer.otpauthUri! }
          }}
          confirm={async (code) =>
            (await unwrap(api.POST('/api/auth/challenge/enroll/confirm', { body: { challenge, code } })))
              .recoveryCodes ?? []
          }
          onDone={() => setStep({ kind: 'password', notice: 'enrolled' })}
        />
        <Button variant="link" onClick={() => setStep({ kind: 'password' })}>
          {t('login.back')}
        </Button>
      </SignInFrame>
    )
  }

  if (step.kind === 'code') {
    const challenge = step.challenge
    return (
      <CodeStep
        error={error}
        onBack={() => {
          setError(null)
          setStep({ kind: 'password' })
        }}
        onVerify={async (code) => {
          setError(null)
          try {
            await verify(challenge, code)
            navigate(from, { replace: true })
          } catch (e) {
            failed(e)
          }
        }}
      />
    )
  }

  const errors = form.formState.errors
  return (
    <SignInFrame subtitle={t('login.title')}>
      {state?.idle && step.notice !== 'enrolled' && (
        <Alert variant="warning" data-testid="idle-locked">
          <TriangleAlert aria-hidden />
          <AlertDescription className="text-current">{t('login.idleLocked')}</AlertDescription>
        </Alert>
      )}
      {step.notice === 'enrolled' && (
        <Alert data-testid="mfa-enrolled">
          <CircleCheck aria-hidden className="text-success" />
          <AlertDescription className="text-foreground">{t('login.enrolled')}</AlertDescription>
        </Alert>
      )}
      {error && (
        <Alert variant="destructive" data-testid="login-error">
          <TriangleAlert aria-hidden />
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      )}
      <form
        noValidate
        className="flex flex-col gap-4"
        onSubmit={form.handleSubmit(async ({ userName, password }) => {
          setError(null)
          try {
            const next = await signIn(userName, password)
            if (next.status === 'SIGNED_IN') {
              navigate(from, { replace: true })
            } else {
              setStep(next.status === 'MFA_REQUIRED'
                ? { kind: 'code', challenge: next.challenge }
                : { kind: 'enroll', challenge: next.challenge })
            }
          } catch (e) {
            // The server answers every refusal with the same LOGIN_FAILED, in the language of the request.
            failed(e)
          }
        })}
      >
        <SignInField
          icon={User}
          placeholder={t('login.userName')}
          autoComplete="username"
          error={errors.userName?.message}
          {...form.register('userName', { validate: (v) => v.trim() !== '' || t('login.userNameRequired') })}
        />
        <SignInField
          icon={LockKeyhole}
          type="password"
          placeholder={t('login.password')}
          autoComplete="current-password"
          error={errors.password?.message}
          {...form.register('password', { validate: (v) => v !== '' || t('login.passwordRequired') })}
        />
        <SubmitButton busy={form.formState.isSubmitting}>{t('login.submit')}</SubmitButton>
      </form>
      {(providers.data ?? []).length > 0 && (
        <>
          <div className="text-muted-foreground flex items-center gap-3 text-sm">
            <Separator className="flex-1" />
            {t('login.orWith')}
            <Separator className="flex-1" />
          </div>
          <div className="flex flex-col gap-2">
            {(providers.data ?? []).map((provider) => (
              <Button
                key={provider.id}
                variant="outline"
                className="w-full"
                data-testid={`oidc-${provider.id}`}
                onClick={() => {
                  setError(null)
                  startProviderSignIn(provider.id!, from).catch(failed)
                }}
              >
                {provider.label}
              </Button>
            ))}
          </div>
        </>
      )}
    </SignInFrame>
  )
}
