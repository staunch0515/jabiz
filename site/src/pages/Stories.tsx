import { useSearchParams } from 'react-router'
import { usePublicQuery } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { FilterBar, type FilterGroup } from '../components/FilterBar'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { StoryCard } from '../components/StoryCard'
import { useLocale, useT } from '../i18n/locale'
import { flag } from '../lib/flags'
import { clearFilters, listParam, readFilters, writeFilter } from '../lib/filters'
import { dictLabel, MEDIA_TYPES } from '../lib/labels'
import { pickText } from '../lib/localized'

const NAMES = ['place', 'theme', 'media'] as const

/**
 * STORIES (brief section 9): the archive, filtered by the places of the people in the stories, by theme and by media
 * type. Places and themes are the published ones, from the data; nothing about them is written in the code.
 */
export function Stories() {
  const t = useT()
  const locale = useLocale()
  const [search, setSearch] = useSearchParams()
  const selected = readFilters(search, NAMES)
  const places = usePublicQuery('culture.public.locations', {}, { limit: 100 })
  const themes = usePublicQuery('culture.public.themes', {}, { limit: 100 })
  const stories = usePublicQuery(
    'culture.public.stories',
    {
      location: listParam(selected.place),
      theme: listParam(selected.theme),
      mediaType: listParam(selected.media),
    },
    { limit: 100 },
  )

  const groups: FilterGroup[] = [
    {
      name: 'place',
      legend: t('stories.place'),
      options: (places.data ?? []).map((p) => ({ value: p.slug ?? '', label: pickText(p.name, locale), icon: flag(p.countryCode) })),
    },
    {
      name: 'theme',
      legend: t('stories.theme'),
      options: (themes.data ?? []).map((th) => ({ value: th.slug ?? '', label: pickText(th.title, locale), icon: th.icon ?? undefined })),
    },
    {
      name: 'media',
      legend: t('stories.media'),
      options: MEDIA_TYPES.map((code) => ({ value: code, label: dictLabel(t, 'mediaType', code) })),
    },
  ]
  const count = stories.data?.length
  return (
    <div className="wrap">
      <PageMeta title={t('stories.title')} description={t('stories.intro')} />
      <div className="page-head">
        <h1>{t('stories.title')}</h1>
        <p className="intro">{t('stories.intro')}</p>
      </div>
      <FilterBar
        label={t('stories.filters')}
        groups={groups}
        selected={selected}
        onChange={(name, values) => setSearch(writeFilter(search, name, values), { replace: true, preventScrollReset: true })}
        onClear={() => setSearch(clearFilters(search, NAMES), { replace: true, preventScrollReset: true })}
        status={count === undefined ? '' : t('stories.results', { count })}
      />
      <QueryState loading={stories.isLoading} error={stories.error} onRetry={() => void stories.refetch()} />
      {count === 0 ? <p>{t('stories.none')}</p> : null}
      <ul className={cards.grid3}>
        {(stories.data ?? []).map((story) => (
          <li key={story.slug}>
            <StoryCard story={story} headingLevel={2} />
          </li>
        ))}
      </ul>
    </div>
  )
}
