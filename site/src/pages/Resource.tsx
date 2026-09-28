import { Link, useParams } from 'react-router'
import { fileUrl } from '../api/client'
import { usePublicQuery, usePublicRow } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { LocalizedText } from '../components/LocalizedText'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { StoryCard } from '../components/StoryCard'
import { useLocale, useT } from '../i18n/locale'
import { dictLabel } from '../lib/labels'
import { pickText } from '../lib/localized'
import { path } from '../lib/paths'
import { NotFound } from './NotFound'
import styles from './Pages.module.css'

/**
 * A teaching resource (brief section 12): what it is for, age and duration, the PDF, the steps readable on the page
 * (not only in the PDF: accessibility) and the stories it works with.
 */
export function Resource() {
  const t = useT()
  const locale = useLocale()
  const slug = useParams().slug ?? ''
  const resource = usePublicRow('culture.public.resource', { slug })
  const stories = usePublicQuery('culture.public.resource_stories', { slug }, { limit: 100 })
  if (resource.row === null) return <NotFound />
  if (!resource.row) {
    return (
      <div className="wrap page-head">
        <QueryState loading={resource.isLoading} error={resource.error} onRetry={() => void resource.refetch()} />
      </div>
    )
  }
  const r = resource.row
  return (
    <>
      <PageMeta title={pickText(r.title, locale)} description={pickText(r.description, locale)} />
      <div className="wrap">
        <div className="page-head enter">
          <p className="crumbs">
            <Link to={path(locale, 'resources')}>{t('nav.resources')}</Link>
          </p>
          <LocalizedText as="h1" value={r.title} />
          <LocalizedText as="p" className="intro" value={r.description} />
          <dl className={styles.facts}>
            <dt className="label">{t('resources.type')}</dt>
            <dd>{dictLabel(t, 'activityType', r.activityType)}</dd>
            <dt className="label">{t('resources.age')}</dt>
            <dd>{dictLabel(t, 'ageGroup', r.ageGroup)}</dd>
            {r.durationMinutes ? (
              <>
                <dt className="label">{t('resources.duration')}</dt>
                <dd>{t('resources.minutes', { count: r.durationMinutes })}</dd>
              </>
            ) : null}
          </dl>
          {r.pdfFileId ? (
            <p>
              <a className="btn btn-primary" href={fileUrl(r.pdfFileId)} download>
                {t('resources.download')} <span aria-hidden="true">↓</span>
              </a>
            </p>
          ) : null}
        </div>
        {r.body ? (
          <section className={`section ${styles.twoCol}`} aria-labelledby="resource-steps">
            <h2 id="resource-steps">{t('resources.steps')}</h2>
            <Markdown value={r.body} />
          </section>
        ) : null}
      </div>
      {stories.data && stories.data.length > 0 ? (
        <section className="section" aria-labelledby="resource-stories">
          <div className="wrap">
            <div className="section-head">
              <h2 id="resource-stories">{t('resources.related')}</h2>
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
