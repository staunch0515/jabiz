import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { usePublicQuery, usePublicRow } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { LocalizedText } from '../components/LocalizedText'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import perspective from '../components/Perspective.module.css'
import { PerspectiveSummary } from '../components/Perspective'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { StoryCard } from '../components/StoryCard'
import { useLocale, useT } from '../i18n/locale'
import { pickText } from '../lib/localized'
import { path } from '../lib/paths'
import { NotFound } from './NotFound'
import styles from './Pages.module.css'

type Mode = 'compare' | 'one'

/**
 * A theme (brief section 8): its question as the page's title, then every perspective on it from the published
 * stories, place by place, to compare side by side or to read one at a time (design section 9.1). How to read is
 * kept in the address, so a view can be shared.
 */
export function Theme() {
  const t = useT()
  const locale = useLocale()
  const slug = useParams().slug ?? ''
  const [search, setSearch] = useSearchParams()
  const mode: Mode = search.get('view') === 'one' ? 'one' : 'compare'
  const [position, setPosition] = useState({ slug, index: 0 })
  // Another theme starts again at its first perspective.
  const index = position.slug === slug ? position.index : 0
  const setIndex = (next: number) => setPosition({ slug, index: next })
  const theme = usePublicRow('culture.public.theme', { slug })
  const perspectives = usePublicQuery(
    'culture.public.theme_perspectives',
    { slug },
    { limit: 100, sort: { field: 'locationSortOrder', direction: 'asc' } },
  )
  const stories = usePublicQuery('culture.public.stories', { theme: [slug] }, { limit: 100 })

  if (theme.row === null) return <NotFound />
  if (!theme.row) {
    return (
      <div className="wrap page-head">
        <QueryState loading={theme.isLoading} error={theme.error} onRetry={() => void theme.refetch()} />
      </div>
    )
  }
  const th = theme.row
  const rows = perspectives.data ?? []
  const current = rows[Math.min(index, Math.max(rows.length - 1, 0))]
  const places = new Set(rows.map((r) => r.locationSlug ?? r.participantSlug)).size

  const setMode = (next: Mode) => {
    setIndex(0)
    const params = new URLSearchParams(search)
    if (next === 'one') params.set('view', 'one')
    else params.delete('view')
    setSearch(params, { replace: true, preventScrollReset: true })
  }

  return (
    <>
      <PageMeta title={pickText(th.title, locale)} description={pickText(th.question, locale)} />
      <div className="wrap">
        <div className="page-head enter">
          <p className="crumbs">
            <Link to={path(locale, 'themes')}>{t('nav.themes')}</Link>
            <span aria-hidden="true">/</span>
            <span>
              <span aria-hidden="true">{th.icon} </span>
              <LocalizedText value={th.title} />
            </span>
          </p>
          <LocalizedText as="h1" value={th.question} />
          <Markdown value={th.intro} className="prose intro" />
          {rows.length > 0 ? (
            <p className="label">
              {t('count.perspectives', { count: rows.length })} · {t('count.places', { count: places })} ·{' '}
              {t('count.stories', { count: stories.data?.length ?? 0 })}
            </p>
          ) : null}
        </div>

        <QueryState loading={perspectives.isLoading} error={perspectives.error} onRetry={() => void perspectives.refetch()} />
        {perspectives.data && rows.length === 0 ? <p>{t('theme.empty')}</p> : null}
        {rows.length > 0 ? (
          <>
            <div className={styles.toolbar}>
              <div className={styles.segmented} role="group" aria-label={t('theme.howToRead')}>
                <button type="button" aria-pressed={mode === 'compare'} onClick={() => setMode('compare')}>
                  {t('theme.sideBySide')}
                </button>
                <button type="button" aria-pressed={mode === 'one'} onClick={() => setMode('one')}>
                  {t('theme.oneAtATime')}
                </button>
              </div>
              <p className="label">{t('theme.orderNote')}</p>
            </div>
            {mode === 'compare' ? (
              <ul className={perspective.grid}>
                {rows.map((row) => (
                  <li key={row.contributionId}>
                    <PerspectiveSummary row={row} storySlug={row.storySlug} headingLevel={2} />
                  </li>
                ))}
              </ul>
            ) : current ? (
              <div className={perspective.one}>
                <ResponsiveImage
                  fileId={current.portraitFileId}
                  alt={pickText(current.portraitAlt, locale)}
                  ratio="portrait"
                  sizes="(max-width: 860px) 100vw, 40vw"
                  placeholder={current.displayName ?? ''}
                />
                <div>
                  <PerspectiveSummary row={current} storySlug={current.storySlug} headingLevel={2} />
                  <div className={perspective.stepper}>
                    <button type="button" className="btn" disabled={index === 0} onClick={() => setIndex(index - 1)}>
                      <span aria-hidden="true">←</span> {t('theme.previous')}
                    </button>
                    <span className="label" aria-live="polite">
                      {t('theme.position', { n: index + 1, total: rows.length })}
                    </span>
                    <button
                      type="button"
                      className="btn"
                      disabled={index >= rows.length - 1}
                      onClick={() => setIndex(index + 1)}
                    >
                      {t('theme.next')} <span aria-hidden="true">→</span>
                    </button>
                  </div>
                </div>
              </div>
            ) : null}
          </>
        ) : null}
      </div>

      {stories.data && stories.data.length > 0 ? (
        <section className="section" aria-labelledby="theme-stories">
          <div className="wrap">
            <div className="section-head">
              <h2 id="theme-stories">{t('theme.stories')}</h2>
            </div>
            <ul className={cards.grid3}>
              {stories.data.map((story) => (
                <li key={story.slug}>
                  <StoryCard story={story} />
                </li>
              ))}
            </ul>
          </div>
        </section>
      ) : null}
    </>
  )
}
