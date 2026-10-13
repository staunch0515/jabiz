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
 * Google's "G", drawn in the text colour: the platform's pages use theme colours only (decision D34), so the mark
 * follows the light and dark appearance like the label beside it.
 */
export function GoogleMark({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false" className={className} fill="currentColor">
      <path d="M21.35 11.1h-9.17v2.98h5.3c-.23 1.4-1.66 4.1-5.3 4.1-3.19 0-5.8-2.64-5.8-5.9s2.61-5.9 5.8-5.9c1.82 0 3.03.77 3.73 1.43l2.54-2.45C16.84 3.85 14.75 2.9 12.18 2.9 6.96 2.9 2.75 7.12 2.75 12.28s4.21 9.38 9.43 9.38c5.44 0 9.05-3.82 9.05-9.2 0-.62-.07-1.09-.15-1.56z" />
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
