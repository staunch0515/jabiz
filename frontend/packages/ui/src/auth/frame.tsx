import { changeLanguage, languages, LANGUAGE_NAMES, type Language } from '@jabiz/client'
import type { LucideIcon } from 'lucide-react'
import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useId, type ComponentProps, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useHasAppearance } from '../components/appearance'
import { Spinner } from '../components/spinner'
import { ThemeToggle } from '../components/theme-toggle'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '../components/ui/card'
import { Input } from '../components/ui/input'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '../components/ui/select'
import { cn } from '../lib/utils'
import { AUTH_NAMESPACE } from './messages'

/**
 * How the blocks lead elsewhere without a router of their own (decision D43; ROADMAP 16c-3): a frontend with a
 * router passes its `navigate` and the address of its paths (its base); without, links are plain links and leaving a
 * page is a full page load.
 */
export interface AuthNavigation {
  /** Goes to a path of this frontend; a full page load when absent. */
  onNavigate?: (path: string) => void
  /** The address of a path of this frontend, as links show it (a router's base path); the path itself when absent. */
  hrefOf?: (path: string) => string
}

/** Goes to a path: the frontend's navigation, else a full page load. */
export function navigateTo(navigation: AuthNavigation, path: string) {
  if (navigation.onNavigate) navigation.onNavigate(path)
  else window.location.assign(navigation.hrefOf ? navigation.hrefOf(path) : path)
}

/** A link to a path of this frontend: the frontend's navigation on a plain click, the browser's otherwise. */
export function AuthLink({
  to,
  onNavigate,
  hrefOf,
  onClick,
  ...anchor
}: { to: string } & AuthNavigation & Omit<ComponentProps<'a'>, 'href'>) {
  const follow = (event: MouseEvent<HTMLAnchorElement>) => {
    onClick?.(event)
    if (!onNavigate || event.defaultPrevented || event.button !== 0) return
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return
    event.preventDefault()
    onNavigate(to)
  }
  return <a {...anchor} href={hrefOf ? hrefOf(to) : to} onClick={follow} />
}

export interface AuthFrameProps {
  /** The application's name, the page's heading (h1). */
  brand: ReactNode
  /** What the page is for, under the name. */
  title: string
  children: ReactNode
  testId?: string
  /** Offers the appearance (light, dark, system) when an AppearanceProvider is around; default true. */
  appearance?: boolean
  className?: string
  /** Under the card and the choices, in small print: the build the page is from, say. */
  footer?: ReactNode
}

/**
 * The frame of the pages before a session (signing in, signing up, the pages of mailed links): the application's
 * name, the page's title and the choice of language (when the application has several) and appearance.
 */
export function AuthFrame({ brand, title, children, testId, appearance = true, className, footer }: AuthFrameProps) {
  const { t, i18n } = useTranslation(AUTH_NAMESPACE)
  const offerAppearance = useHasAppearance() && appearance
  return (
    <main className={cn('bg-background text-foreground flex min-h-screen flex-col items-center px-4 pt-20', className)}>
      <Card className="w-full max-w-sm" data-testid={testId}>
        <CardHeader className="text-center">
          <CardTitle>
            <h1 className="text-2xl font-semibold tracking-tight">{brand}</h1>
          </CardTitle>
          <CardDescription>{title}</CardDescription>
        </CardHeader>
        <CardContent className="flex flex-col gap-4">{children}</CardContent>
      </Card>
      {(languages.length > 1 || offerAppearance) && (
        <div className="mt-4 flex items-center gap-2">
          {languages.length > 1 && (
            <Select value={i18n.language} onValueChange={(lang) => void changeLanguage(lang as Language)}>
              <SelectTrigger size="sm" aria-label={t('common.language')}>
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
          )}
          {offerAppearance && <ThemeToggle />}
        </div>
      )}
      {footer && <p className="text-muted-foreground mt-4 text-xs" data-testid="auth-footer">{footer}</p>}
    </main>
  )
}

/** A text field of these forms: an icon, the placeholder as its name, and its error under it. */
export function AuthField({
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

/** The submit button of these forms, busy while the form is sent. */
export function AuthSubmit({ busy, children }: { busy: boolean; children: ReactNode }) {
  return (
    <Button type="submit" size="lg" className="w-full" disabled={busy} aria-busy={busy || undefined}>
      {busy && <Spinner className="text-current" />}
      {children}
    </Button>
  )
}

/** A refusal shown above a form. */
export function AuthError({ message, testId }: { message: string; testId?: string }) {
  return (
    <Alert variant="destructive" data-testid={testId}>
      <TriangleAlert aria-hidden />
      <AlertDescription>{message}</AlertDescription>
    </Alert>
  )
}

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
  const { t } = useTranslation(AUTH_NAMESPACE)
  return (
    <Button asChild size="lg" className="w-full">
      <a href={path} data-testid="auth-continue">
        {label ?? t('account.continue')}
      </a>
    </Button>
  )
}

/** The class of the small links under a form. */
export const AUTH_LINK_CLASS = 'text-primary self-center text-sm underline-offset-4 hover:underline'
