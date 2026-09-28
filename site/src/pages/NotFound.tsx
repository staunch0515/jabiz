import { Link } from 'react-router'
import { PageMeta } from '../components/PageMeta'
import { useLocale, useT } from '../i18n/locale'
import { path } from '../lib/paths'

/** An address that is not a page, or a page that is not public (any more). */
export function NotFound() {
  const t = useT()
  const locale = useLocale()
  return (
    <div className="wrap page-head">
      <PageMeta title={t('common.notFoundTitle')} />
      <meta name="robots" content="noindex" />
      <h1>{t('common.notFoundTitle')}</h1>
      <p className="intro">{t('common.notFoundBody')}</p>
      <p>
        <Link className="btn" to={path(locale)}>
          {t('common.backHome')}
        </Link>
      </p>
    </div>
  )
}
