import { MailLinkPage } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { useLocation } from 'react-router'
import { useRouterNavigation } from '../../auth/routerNavigation'

/**
 * A page of a mailed link in the admin (`/auth/<page>?entry=<entry>&token=<token>`, decision D36 implementation
 * note 13): @jabiz/ui's MailLinkPage (decision D43) on the admin's router. It takes links of every entry, so links
 * mailed before the entries' own pages existed keep working; once done it leads to the entry's frontend.
 */
export function MailLinkRoute({ page }: { page: 'verify-email' | 'join' | 'password-reset' | 'invitation' }) {
  const { t } = useTranslation()
  const navigation = useRouterNavigation()
  const location = useLocation()
  return (
    <MailLinkPage
      {...navigation}
      page={page}
      search={location.search}
      brand={t('app.title')}
      appearance={false}
      signInPath="/login"
      passwordResetPath="/auth/password-reset"
    />
  )
}
