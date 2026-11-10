import { Button, Card, CardContent, CardDescription, CardHeader, CardTitle } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { BENEFITS_PATH, BENEFITS_QUERY, readAccess } from '../benefits/api'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'
import '../shell/messages'

/**
 * The way to the sponsored benefits for a Sponsor who holds neither benefit role (F-F3): the area is no menu entry for
 * them, so the hub's overview leads to the catalog, where the card says which node of the platform's map opens each
 * role. Nothing for a holder of a role (the menu has the area) or while the answer is not in.
 */
export function BenefitsEntryCard() {
  const { t } = useTranslation(SPONSOR)
  const access = useQuery({ queryKey: [...BENEFITS_QUERY, 'access'], queryFn: readAccess, staleTime: 60_000 })
  if (!access.data || access.data.some((row) => row.open)) return null
  return (
    <Card data-testid="profile-benefits-entry">
      <CardHeader>
        <CardTitle className="text-base">{t('shell.benefitsEntry.title')}</CardTitle>
        <CardDescription>{t('shell.benefitsEntry.hint')}</CardDescription>
      </CardHeader>
      <CardContent>
        <Button asChild variant="outline" size="sm">
          <Link to={BENEFITS_PATH} data-testid="profile-benefits-link">{t('shell.benefitsEntry.link')}</Link>
        </Button>
      </CardContent>
    </Card>
  )
}
