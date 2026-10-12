import { StepUpProvider as StepUpBlock } from '@jabiz/ui'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useRouterNavigation } from './routerNavigation'

/**
 * Asks for a second factor when an operation requires a recent one (docs/design/10-security.md section 10):
 * @jabiz/ui's StepUpProvider (decision D43), pointing users without two-step verification to the admin's security
 * page.
 */
export function StepUpProvider({ children }: { children: ReactNode }) {
  const { t } = useTranslation()
  const navigation = useRouterNavigation()
  return (
    <StepUpBlock {...navigation} securityPath="/account/security" securityLabel={t('app.security')}>
      {children}
    </StepUpBlock>
  )
}
