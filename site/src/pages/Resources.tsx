import { Link, useSearchParams } from 'react-router'
import { usePublicQuery } from '../api/hooks'
import { FilterBar, type FilterGroup } from '../components/FilterBar'
import { LocalizedText } from '../components/LocalizedText'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { useLocale, useT } from '../i18n/locale'
import { clearFilters, listParam, readFilters, writeFilter } from '../lib/filters'
import { ACTIVITY_TYPES, AGE_GROUPS, dictLabel } from '../lib/labels'
import { path } from '../lib/paths'
import styles from './Pages.module.css'

const NAMES = ['type', 'age'] as const

/** RESOURCES (brief section 12): teaching activities, filtered by type and age; editors add more at any time. */
export function Resources() {
  const t = useT()
  const locale = useLocale()
  const [search, setSearch] = useSearchParams()
  const selected = readFilters(search, NAMES)
  const resources = usePublicQuery(
    'culture.public.resources',
    { activityType: listParam(selected.type), ageGroup: listParam(selected.age) },
    { limit: 100 },
  )
  const groups: FilterGroup[] = [
    { name: 'type', legend: t('resources.type'), options: ACTIVITY_TYPES.map((c) => ({ value: c, label: dictLabel(t, 'activityType', c) })) },
    { name: 'age', legend: t('resources.age'), options: AGE_GROUPS.map((c) => ({ value: c, label: dictLabel(t, 'ageGroup', c) })) },
  ]
  const count = resources.data?.length
  return (
    <div className="wrap">
      <PageMeta title={t('resources.title')} description={t('resources.intro')} />
      <div className="page-head">
        <h1>{t('resources.title')}</h1>
        <p className="intro">{t('resources.intro')}</p>
      </div>
      <FilterBar
        label={t('resources.title')}
        groups={groups}
        selected={selected}
        onChange={(name, values) => setSearch(writeFilter(search, name, values), { replace: true, preventScrollReset: true })}
        onClear={() => setSearch(clearFilters(search, NAMES), { replace: true, preventScrollReset: true })}
        status={count === undefined ? '' : t('resources.results', { count })}
      />
      <QueryState loading={resources.isLoading} error={resources.error} onRetry={() => void resources.refetch()} />
      {count === 0 ? <p>{t('resources.none')}</p> : null}
      <ul className={styles.resourceList}>
        {(resources.data ?? []).map((r) => (
          <li key={r.slug}>
            <p className="label">
              {[
                dictLabel(t, 'activityType', r.activityType),
                dictLabel(t, 'ageGroup', r.ageGroup),
                r.durationMinutes ? t('resources.minutes', { count: r.durationMinutes }) : '',
              ]
                .filter(Boolean)
                .join(' · ')}
            </p>
            <h2>
              <Link to={path(locale, 'resources', r.slug)}>
                <LocalizedText value={r.title} />
              </Link>
            </h2>
            <LocalizedText as="p" value={r.description} />
          </li>
        ))}
      </ul>
    </div>
  )
}
