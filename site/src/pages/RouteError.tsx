import { useRouteError } from 'react-router'
import { PageMeta } from '../components/PageMeta'
import { useT } from '../i18n/locale'

/**
 * A page that could not be shown: most often its code could not be downloaded (a dropped connection, or a new
 * release replaced the files the open page knew). Reloading fetches the current ones.
 */
export function RouteError() {
  const t = useT()
  const error = useRouteError()
  if (import.meta.env.DEV) console.error(error)
  return (
    <div className="wrap page-head">
      <PageMeta title={t('common.pageErrorTitle')} />
      <h1>{t('common.pageErrorTitle')}</h1>
      <p className="intro">{t('common.pageErrorBody')}</p>
      <p>
        <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
          {t('common.reload')}
        </button>
      </p>
    </div>
  )
}
