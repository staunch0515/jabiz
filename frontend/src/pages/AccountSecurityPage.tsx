import { api, unwrap, type IdentityLinked } from '@jabiz/client'
import {
  AUTH_NAMESPACE,
  Card,
  CardContent,
  CardHeader,
  CardTitle,
  ChangePasswordForm,
  LinkedIdentities,
  MfaSettings,
  SessionList,
  type OidcProvider,
} from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router'
import AdminPage from '../layout/AdminPage'

/** Where the security page is, and where a round trip to link a provider account comes back to. */
export const ACCOUNT_SECURITY_PATH = '/account/security'

/**
 * What a section may depend on: the identity providers offered at the administration's entry, as the sign-in page
 * asks for them, and what a link round trip just finished (the identity provider's return page passes it on).
 */
export interface AccountContext {
  providers: readonly OidcProvider[]
  linked: IdentityLinked | null
}

/**
 * A section of the security page: its heading and content, the `name` of its test id and heading id, and whether it
 * is shown (always, when absent).
 */
export interface AccountSection {
  name: string
  title: (t: (key: string, options?: Record<string, unknown>) => string) => string
  Content: (props: AccountContext) => ReactNode
  shown?: (context: AccountContext) => boolean
}

/**
 * The sections of the security page, in order (phase 15c-2): a new section of the user's own account (a mail
 * preference, the language) is one more entry here, a building block of @jabiz/ui where an application's account page
 * can use it too.
 */
// eslint-disable-next-line react-refresh/only-export-components
export const ACCOUNT_SECTIONS: AccountSection[] = [
  { name: 'mfa', title: (t) => t('mfa.title'), Content: () => <MfaSettings className="max-w-2xl" /> },
  {
    name: 'password',
    title: (t) => t('password.changeTitle', { ns: AUTH_NAMESPACE }),
    Content: () => <ChangePasswordForm className="max-w-md" />,
  },
  {
    name: 'sessions',
    title: (t) => t('sessions.title', { ns: AUTH_NAMESPACE }),
    Content: () => <SessionList className="max-w-3xl" />,
  },
  {
    // Only where identity providers are offered (10 section 12, phase 16f): without one there is nothing to link.
    name: 'identities',
    title: (t) => t('identities.title', { ns: AUTH_NAMESPACE }),
    Content: ({ providers, linked }) => (
      <LinkedIdentities className="max-w-3xl" returnTo={ACCOUNT_SECURITY_PATH} providers={providers} linked={linked} />
    ),
    shown: ({ providers }) => providers.length > 0,
  },
]

/**
 * The signed-in user's own security (docs/design/10-security.md): two-step verification (section 9), the password
 * (section 15.8, decision D43 item 3), the sessions (section 15.9) and the linked provider accounts (section 12, where
 * providers are offered), each a section of {@link ACCOUNT_SECTIONS}.
 */
export default function AccountSecurityPage() {
  const { t, i18n } = useTranslation()
  const location = useLocation()
  // As the sign-in page asks (the same query): the providers offered at the administration's entry.
  const providers = useQuery({
    queryKey: ['auth', 'oidc', 'providers', i18n.language],
    queryFn: () => unwrap(api.GET('/api/auth/oidc/providers')),
    staleTime: Infinity,
  })
  const context: AccountContext = {
    providers: Array.isArray(providers.data) ? providers.data : [],
    linked: (location.state as { linked?: IdentityLinked } | null)?.linked ?? null,
  }
  return (
    <AdminPage title={t('app.security')}>
      {ACCOUNT_SECTIONS.filter((section) => section.shown?.(context) ?? true).map(({ name, title, Content }) => (
        <section key={name} aria-labelledby={`account-section-${name}-title`} data-testid={`account-section-${name}`}>
          <Card>
            <CardHeader>
              <CardTitle>
                <h2 id={`account-section-${name}-title`} className="text-base font-semibold">
                  {title(t)}
                </h2>
              </CardTitle>
            </CardHeader>
            <CardContent>
              <Content {...context} />
            </CardContent>
          </Card>
        </section>
      ))}
    </AdminPage>
  )
}
