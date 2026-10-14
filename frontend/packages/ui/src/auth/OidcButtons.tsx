import { api, session, sitePath, startProviderSignIn, unwrap, type components } from '@jabiz/client'
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

/** The mark drawn before a provider's label, by its kind; none for a provider of no known brand. */
export function ProviderMark({ kind, className = 'size-4' }: { kind?: string | null; className?: string }) {
  return kind === 'google' ? <GoogleMark className={className} /> : null
}

/**
 * Signing in through an identity provider (docs/design/10-security.md section 12): a button per provider, under an
 * "or" separator; nothing when none is offered. A provider of a known kind (`google`) shows its mark beside its label.
 */
export function OidcButtons({ returnTo, providers, start = startProviderSignIn, onError }: OidcButtonsProps) {
  const { t } = useTranslation(AUTH_NAMESPACE)
  const offered = useOidcProviders(providers)
  if (offered.length === 0) return null
  return (
    <>
      <div className="text-muted-foreground flex items-center gap-3 text-sm">
        <Separator className="flex-1" />
        {t('login.orWith')}
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
            onClick={() => {
              onError?.(null)
              start(provider.id!, sitePath(returnTo)).catch((e: unknown) => onError?.(e))
            }}
          >
            <ProviderMark kind={provider.kind} />
            {provider.label}
          </Button>
        ))}
      </div>
    </>
  )
}
