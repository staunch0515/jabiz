import { useTranslation } from 'react-i18next'
import { legalPath, type LegalKind } from './legal'
import { QB } from './messages'
import { Link } from './router'

/** Links to the help centre and the texts of a front end, as a small navigation (a footer, the account page). */
export function LegalLinks({ kinds, className }: { kinds: readonly LegalKind[]; className?: string }) {
  const { t } = useTranslation(QB)
  return (
    <nav aria-label={t('legal.links')} className={className} data-testid="legal-links">
      <ul className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-sm">
        <li><Link to="/help" className="hover:text-foreground underline">{t('help.title')}</Link></li>
        {kinds.map((kind) => (
          <li key={kind}>
            <Link to={legalPath(kind)} className="hover:text-foreground underline">{t(`legal.kinds.${kind}`)}</Link>
          </li>
        ))}
      </ul>
    </nav>
  )
}
