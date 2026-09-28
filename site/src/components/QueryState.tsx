import { useT } from '../i18n/locale'

/** While a part of the page loads, or when it could not (with a way to try again). */
export function QueryState({ loading, error, onRetry }: { loading: boolean; error: unknown; onRetry?: () => void }) {
  const t = useT()
  if (loading) {
    return (
      <p className="label" role="status">
        {t('common.loading')}
      </p>
    )
  }
  if (error) {
    return (
      <div role="alert" className="prose">
        <p>{t('common.error')}</p>
        {onRetry ? (
          <p>
            <button type="button" className="btn" onClick={onRetry}>
              {t('common.retry')}
            </button>
          </p>
        ) : null}
      </div>
    )
  }
  return null
}
