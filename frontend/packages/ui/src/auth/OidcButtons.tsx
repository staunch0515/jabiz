import { api, startProviderSignIn, unwrap, type components } from '@jabiz/client'
import { useEffect, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { Button } from '../components/ui/button'
import { Separator } from '../components/ui/separator'
import { AUTH_NAMESPACE } from './messages'

export type OidcProvider = components['schemas']['ProviderEntry']

/** The identity providers offered (`GET /api/auth/oidc/providers`), loaded once; none while loading or on failure. */
export function useOidcProviders(given?: readonly OidcProvider[]): readonly OidcProvider[] {
  const [loaded, setLoaded] = useState<readonly OidcProvider[]>([])
  useEffect(() => {
    if (given) return undefined
    let live = true
    unwrap(api.GET('/api/auth/oidc/providers'))
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
 * Signing in through an identity provider (docs/design/10-security.md section 12): a button per provider, under an
 * "or" separator; nothing when none is offered.
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
            onClick={() => {
              onError?.(null)
              start(provider.id!, returnTo).catch((e: unknown) => onError?.(e))
            }}
          >
            {provider.label}
          </Button>
        ))}
      </div>
    </>
  )
}
