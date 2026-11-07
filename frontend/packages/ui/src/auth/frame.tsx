import type { LucideIcon } from 'lucide-react'
import { CircleCheck, TriangleAlert } from 'lucide-react'
import { useId, type ComponentProps, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Spinner } from '../components/spinner'
import { Alert, AlertDescription } from '../components/ui/alert'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { EntryLayout, type EntryLayoutProps } from './entry'
import { AUTH_NAMESPACE } from './messages'
import { PasswordInput } from './PasswordInput'

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

/** The props of {@link AuthFrame}: those of the entry pages' template (phase 17zk). */
export type AuthFrameProps = EntryLayoutProps

/**
 * The frame of the pages before a session (signing in, signing up, the pages of mailed links): since phase 17zk the
 * entry pages' template {@link EntryLayout} — the application's name, the page's title, the choice of language (when
 * the application has several) at the top right and of appearance in the footer, and what an
 * {@link EntryLayoutProvider} around it adds. The props of before keep their meaning.
 */
export function AuthFrame(props: AuthFrameProps) {
  return <EntryLayout {...props} />
}

/**
 * A text field of these forms: an icon, the placeholder as its name, and its error under it. A password field
 * (`type="password"`) has a button at its end that shows what was typed (`PasswordInput`, phase 17p).
 */
export function AuthField({
  icon: Icon,
  error,
  type,
  ...input
}: { icon: LucideIcon; error?: string } & ComponentProps<'input'>) {
  const errorId = useId()
  const field = {
    ...input,
    'aria-label': input.placeholder,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? errorId : undefined,
    className: 'h-10 pl-9',
  }
  return (
    <div className="flex flex-col gap-1.5">
      <div className="relative">
        <Icon aria-hidden className="text-muted-foreground pointer-events-none absolute top-1/2 left-3 size-4 -translate-y-1/2" />
        {type === 'password' ? <PasswordInput {...field} /> : <Input {...field} type={type} />}
      </div>
      {error && (
        <p id={errorId} className="text-destructive text-sm">
          {error}
        </p>
      )}
    </div>
  )
}

/**
 * The submit button of these forms, busy while the form is sent; while the server asks to wait (`wait` seconds after
 * 429 RATE_LIMITED, phase 17zk) it is held and says how long.
 */
export function AuthSubmit({ busy, children, wait = 0 }: { busy: boolean; children: ReactNode; wait?: number }) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const waiting = wait > 0
  return (
    <Button type="submit" size="lg" className="w-full" disabled={busy || waiting} aria-busy={busy || undefined}
      data-waiting={waiting ? wait : undefined}>
      {busy && <Spinner className="text-current" />}
      {waiting ? t('common.retryIn', { seconds: wait }) : children}
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
