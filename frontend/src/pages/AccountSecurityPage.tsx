import { AUTH_NAMESPACE, Card, CardContent, CardHeader, CardTitle, ChangePasswordForm, MfaSettings, SessionList } from '@jabiz/ui'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import AdminPage from '../layout/AdminPage'

/** A section of the security page: its heading and content, and the `name` of its test id and heading id. */
export interface AccountSection {
  name: string
  title: (t: (key: string, options?: Record<string, unknown>) => string) => string
  Content: () => ReactNode
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
]

/**
 * The signed-in user's own security (docs/design/10-security.md): two-step verification (section 9), the password
 * (section 15.8, decision D43 item 3) and the sessions (section 15.9), each a section of {@link ACCOUNT_SECTIONS}.
 */
export default function AccountSecurityPage() {
  const { t } = useTranslation()
  return (
    <AdminPage title={t('app.security')}>
      {ACCOUNT_SECTIONS.map(({ name, title, Content }) => (
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
              <Content />
            </CardContent>
          </Card>
        </section>
      ))}
    </AdminPage>
  )
}
