import { usePublicQuery } from '../api/hooks'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { ThemeList } from '../components/ThemeList'
import { useT } from '../i18n/locale'

/** THEMES (brief section 8): the questions we ask everywhere, each with how many places answer it. */
export function Themes() {
  const t = useT()
  const themes = usePublicQuery('culture.public.themes', {}, { limit: 100 })
  return (
    <div className="wrap">
      <PageMeta title={t('themes.title')} description={t('themes.intro')} />
      <div className="page-head">
        <h1>{t('themes.title')}</h1>
        <p className="intro">{t('themes.intro')}</p>
      </div>
      <QueryState loading={themes.isLoading} error={themes.error} onRetry={() => void themes.refetch()} />
      <ThemeList themes={themes.data ?? []} />
    </div>
  )
}
