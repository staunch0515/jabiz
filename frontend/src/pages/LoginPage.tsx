import { api, unwrap, lastUserName, useAuth, type SignInStep, startProviderSignIn } from '@jabiz/client'
import { SignInForm } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { Navigate, useLocation, useNavigate } from 'react-router'
import { useRouterNavigation } from '../auth/routerNavigation'
import { extension } from '../extension'
import { homePath } from '../extension/registry'
import { BrandTitle } from '../extension/BrandTitle'

/**
 * Signing in (docs/design/10-security.md sections 4 and 9): @jabiz/ui's SignInForm (decision D43) on the admin's
 * router — the page to return to and the second step after an identity provider come in the location's state, the
 * idle lock says why and fills in the last user name.
 */
export default function LoginPage() {
  const { t, i18n } = useTranslation()
  const { signIn, verify, signedIn, ready } = useAuth()
  const navigate = useNavigate()
  const navigation = useRouterNavigation()
  const location = useLocation()
  const state = location.state as { from?: string; idle?: boolean; step?: SignInStep } | null
  // Without a page to return to, the application's home (12 section 9), as the root path leads there too.
  const from = state?.from ?? homePath(extension)
  const providers = useQuery({
    queryKey: ['auth', 'oidc', 'providers', i18n.language],
    queryFn: () => unwrap(api.GET('/api/auth/oidc/providers')),
    staleTime: Infinity,
  })

  if (ready && signedIn) return <Navigate to={from} replace />
  // The commit this build is from (JABIZ_BUILD_COMMIT at build time, 17 section 3): which version runs, at a glance.
  const commit = String(import.meta.env.VITE_JABIZ_BUILD_COMMIT ?? '').trim().slice(0, 12)

  return (
    <SignInForm
      {...navigation}
      brand={<BrandTitle brand={extension.brand} />}
      appearance={false}
      returnTo={from}
      onSignedIn={(path) => void navigate(path, { replace: true })}
      initialStep={state?.step}
      notice={state?.idle ? 'idle' : undefined}
      initialUserName={state?.idle ? lastUserName() : undefined}
      passwordResetPath="/auth/password-reset"
      providers={providers.data ?? []}
      startProvider={startProviderSignIn}
      auth={{ signIn, verify }}
      footer={commit ? t('app.build', { commit }) : undefined}
    />
  )
}
