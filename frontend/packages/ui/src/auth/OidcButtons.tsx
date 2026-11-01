import { api, session, sitePath, startProviderSignIn, unwrap, type components } from '@jabiz/client'
import { KeyRound } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import { Separator } from '../components/ui/separator'
import { AUTH_NAMESPACE } from './messages'

export type OidcProvider = components['schemas']['ProviderEntry']

/**
 * The identity providers offered to the frontend's sign-in entry (`GET /api/auth/oidc/providers?entry=`, decision D43
 * item 5: only those with a callback for it), loaded once; none while loading or on failure.
 */
export function useOidcProviders(given?: readonly OidcProvider[]): readonly OidcProvider[] {
  const [loaded, setLoaded] = useState<readonly OidcProvider[]>([])
  useEffect(() => {
    if (given) return undefined
    let live = true
    unwrap(api.GET('/api/auth/oidc/providers', { params: { query: { entry: session.entry() } } }))
      .then((list) => {
        if (live) setLoaded(list ?? [])
      })
      .catch(() => undefined)
    return () => {
      live = false
    }
  }, [given])
  return given ?? loaded
}

export interface OidcButtonsProps {
  /** Where to go once signed in (kept across the round trip; only paths of this site). */
  returnTo: string
  /** The providers when the caller has them; loaded otherwise. */
  providers?: readonly OidcProvider[]
  /** Starts the round trip; `startProviderSignIn` of `@jabiz/client` (the frontend's entry) by default. */
  start?: (providerId: string, returnTo: string) => Promise<void>
  /** Called when starting fails (refused, offline); `null` before each attempt. */
  onError?: (error: unknown) => void
  /**
   * `buttons` (the default): a full-width button per provider, its mark beside its label. `icons`: a row of round
   * buttons under "Other ways to sign in", each named by its provider's label (decision D61 item 5).
   */
  layout?: 'buttons' | 'icons'
  /**
   * What the buttons are offered for: signing in (the default) or signing up, which only changes the words over them
   * (a provider's first sign-in opens the account where the entry allows it; decision D39).
   */
  purpose?: 'signIn' | 'signUp'
}

/**
 * Google's four-colour "G", as Google's sign-in branding guidelines require. A third-party brand mark keeps its own
 * colours (decision D44, amending D34): only the mark's shapes are fixed, the label and the button use theme colours.
 */
export function GoogleMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" aria-hidden="true" focusable="false" className={className}>
      <path fill="#EA4335" d="M24 9.5c3.54 0 6.71 1.22 9.21 3.6l6.85-6.85C35.9 2.38 30.47 0 24 0 14.62 0 6.51 5.38 2.56 13.22l7.98 6.19C12.43 13.72 17.74 9.5 24 9.5z" />
      <path fill="#4285F4" d="M46.98 24.55c0-1.57-.15-3.09-.38-4.55H24v9.02h12.94c-.58 2.96-2.26 5.48-4.78 7.18l7.73 6c4.51-4.18 7.09-10.36 7.09-17.65z" />
      <path fill="#FBBC05" d="M10.53 28.59c-.48-1.45-.76-2.99-.76-4.59s.27-3.14.76-4.59l-7.98-6.19C.92 16.46 0 20.12 0 24c0 3.88.92 7.54 2.56 10.78l7.97-6.19z" />
      <path fill="#34A853" d="M24 48c6.48 0 11.93-2.13 15.89-5.81l-7.73-6c-2.15 1.45-4.92 2.3-8.16 2.3-6.26 0-11.57-4.22-13.47-9.91l-7.98 6.19C6.51 42.62 14.62 48 24 48z" />
    </svg>
  )
}

/** Facebook's round "f" in its blue, as Meta's brand guidelines draw it (decision D44): the "f" is the white behind. */
export function FacebookMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" className={className}>
      <circle cx="12" cy="12" r="11" fill="#FFFFFF" />
      <path fill="#0866FF" d="M24 12.07C24 5.41 18.63 0 12 0S0 5.4 0 12.07C0 18.1 4.39 23.1 10.13 24v-8.44H7.08v-3.49h3.04V9.41c0-3.02 1.8-4.7 4.54-4.7 1.31 0 2.68.24 2.68.24v2.97h-1.5c-1.5 0-1.96.93-1.96 1.89v2.26h3.32l-.53 3.5h-2.8V24C19.62 23.1 24 18.1 24 12.07" />
    </svg>
  )
}

/** LINE's speech bubble with its name, in LINE's green (decision D44): the letters are the white behind. */
export function LineMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" className={className}>
      <path fill="#FFFFFF" d="M3 7h18v7H3z" />
      <path fill="#06C755" d="M19.365 9.863c.349 0 .63.285.63.631 0 .345-.281.63-.63.63H17.61v1.125h1.755c.349 0 .63.283.63.63 0 .344-.281.629-.63.629h-2.386c-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63h2.386c.346 0 .627.285.627.63 0 .349-.281.63-.63.63H17.61v1.125h1.755zm-3.855 3.016c0 .27-.174.51-.432.596-.064.021-.133.031-.199.031-.211 0-.391-.09-.51-.25l-2.443-3.317v2.94c0 .344-.279.629-.631.629-.346 0-.626-.285-.626-.629V8.108c0-.27.173-.51.43-.595.06-.023.136-.033.194-.033.195 0 .375.104.495.254l2.462 3.33V8.108c0-.345.282-.63.63-.63.345 0 .63.285.63.63v4.771zm-5.741 0c0 .344-.282.629-.631.629-.345 0-.627-.285-.627-.629V8.108c0-.345.282-.63.63-.63.346 0 .628.285.628.63v4.771zm-2.466.629H4.917c-.345 0-.63-.285-.63-.629V8.108c0-.345.285-.63.63-.63.348 0 .63.285.63.63v4.141h1.756c.348 0 .629.283.629.63 0 .344-.282.629-.629.629M24 10.314C24 4.943 18.615.572 12 .572S0 4.943 0 10.314c0 4.811 4.27 8.842 10.035 9.608.391.082.923.258 1.058.59.12.301.079.766.038 1.08l-.164 1.02c-.045.301-.24 1.186 1.049.645 1.291-.539 6.916-4.078 9.436-6.975C23.176 14.393 24 12.458 24 10.314" />
    </svg>
  )
}

/**
 * Apple's logo, as Apple's Human Interface Guidelines for Sign in with Apple draw it (decision D67): in the colour of
 * the button's text, black on a light button and white on a dark one, as Apple's white-outline and black styles are.
 */
export function AppleMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" className={className}>
      <path fill="currentColor" d="M12.152 6.896c-.948 0-2.415-1.078-3.96-1.04-2.04.027-3.91 1.183-4.961 3.014-2.117 3.675-.546 9.103 1.519 12.09 1.013 1.454 2.208 3.09 3.792 3.039 1.52-.065 2.09-.987 3.935-.987 1.831 0 2.35.987 3.96.948 1.637-.026 2.676-1.48 3.676-2.948 1.156-1.688 1.636-3.325 1.662-3.415-.039-.013-3.182-1.221-3.22-4.857-.026-3.04 2.48-4.494 2.597-4.559-1.429-2.09-3.623-2.324-4.39-2.376-2-.156-3.675 1.09-4.61 1.09zM15.53 3.83c.843-1.012 1.4-2.427 1.245-3.83-1.207.052-2.662.805-3.532 1.818-.78.896-1.454 2.338-1.273 3.714 1.338.104 2.715-.688 3.559-1.701" />
    </svg>
  )
}

/** The mark of a provider of a known brand, by its kind (`google`, `facebook`, `line`, `apple`); none for any other. */
export function ProviderMark({ kind, className = 'size-4' }: { kind?: string | null; className?: string }) {
  if (kind === 'google') return <GoogleMark className={className} />
  if (kind === 'apple') return <AppleMark className={className} />
  if (kind === 'facebook') return <FacebookMark className={className} />
  if (kind === 'line') return <LineMark className={className} />
  return null
}

/**
 * Signing in through an identity provider (docs/design/10-security.md section 12): a button per provider, under an
 * "or" separator; nothing when none is offered. A provider of a known kind (`google`) shows its mark beside its label;
 * Apple's button says Apple's own title in the page's language.
 */
export function OidcButtons({ returnTo, providers, start = startProviderSignIn, onError, layout = 'buttons',
  purpose = 'signIn' }: OidcButtonsProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const offered = useOidcProviders(providers)
  if (offered.length === 0) return null
  // Apple allows only its own titles on its button (decision D67), whatever the provider's label says.
  const label = (provider: OidcProvider) => provider.kind === 'apple'
    ? t(purpose === 'signUp' ? 'signUp.withApple' : 'login.withApple') : provider.label
  const go = (provider: OidcProvider) => {
    onError?.(null)
    start(provider.id!, sitePath(returnTo)).catch((e: unknown) => onError?.(e))
  }
  if (layout === 'icons') {
    return (
      <div className="flex flex-col gap-3" data-testid="oidc-icons">
        <div className="text-muted-foreground flex items-center gap-3 text-sm">
          <Separator className="flex-1" />
          {t(purpose === 'signUp' ? 'signUp.otherWays' : 'login.otherWays')}
          <Separator className="flex-1" />
        </div>
        <ul className="flex flex-wrap items-center justify-center gap-4">
          {offered.map((provider) => (
            <li key={provider.id}>
              <Button variant="outline" size="icon" className="size-11 rounded-full" aria-label={label(provider)}
                title={label(provider)} data-testid={`oidc-${provider.id}`} data-kind={provider.kind ?? 'generic'}
                onClick={() => go(provider)}>
                {ProviderMark({ kind: provider.kind, className: 'size-6' }) ?? <KeyRound aria-hidden className="size-5" />}
              </Button>
            </li>
          ))}
        </ul>
      </div>
    )
  }
  return (
    <>
      <div className="text-muted-foreground flex items-center gap-3 text-sm">
        <Separator className="flex-1" />
        {t(purpose === 'signUp' ? 'signUp.orWith' : 'login.orWith')}
        <Separator className="flex-1" />
      </div>
      <div className="flex flex-col gap-2">
        {offered.map((provider) => (
          <Button
            key={provider.id}
            variant="outline"
            className="w-full"
            data-testid={`oidc-${provider.id}`}
            data-kind={provider.kind ?? 'generic'}
            onClick={() => go(provider)}
          >
            <ProviderMark kind={provider.kind} />
            {label(provider)}
          </Button>
        ))}
      </div>
    </>
  )
}
