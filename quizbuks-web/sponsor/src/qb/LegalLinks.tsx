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

/**
 * The same links as plain anchors for the entry pages' footer (phase 17zk, gap 8): the template's `links` slot is a
 * navigation of its own already. They open the public pages of this front end (readable without signing in).
 */
export function EntryLegalLinks({ kinds }: { kinds: readonly LegalKind[] }) {
  const { t } = useTranslation(QB)
  const linkClass = 'hover:text-foreground underline-offset-4 hover:underline'
  return (
    <>
      <Link to="/help" className={linkClass} data-testid="entry-link-help">{t('help.title')}</Link>
      {kinds.map((kind) => (
        <Link key={kind} to={legalPath(kind)} className={linkClass} data-testid={`entry-link-${kind}`}>
          {t(`legal.kinds.${kind}`)}
        </Link>
      ))}
    </>
  )
}
