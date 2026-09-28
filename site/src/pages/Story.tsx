import { useEffect } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { usePublicQuery, usePublicRow } from '../api/hooks'
import { LocalizedText } from '../components/LocalizedText'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { MediaList, PerspectiveFull } from '../components/Perspective'
import { Place } from '../components/Place'
import { QueryState } from '../components/QueryState'
import { ReflectionBlock } from '../components/ReflectionBlock'
import { VideoEmbed } from '../components/VideoEmbed'
import { useLocale, useT } from '../i18n/locale'
import { formatDate, isoDate } from '../lib/dates'
import { dictLabel } from '../lib/labels'
import { pick, pickText } from '../lib/localized'
import { path } from '../lib/paths'
import { NotFound } from './NotFound'
import styles from './Pages.module.css'

/**
 * A story (brief section 10): title, themes and correspondents, the player, About this story, the text, each
 * participant's perspective, and the three reflection questions.
 */
export function Story() {
  const t = useT()
  const locale = useLocale()
  const location = useLocation()
  const slug = useParams().slug ?? ''
  const story = usePublicRow('culture.public.story', { slug })
  const themes = usePublicQuery('culture.public.story_themes', { slug }, { limit: 100 })
  const perspectives = usePublicQuery('culture.public.story_perspectives', { slug }, { limit: 100 })
  const media = usePublicQuery('culture.public.story_media', { slug }, { limit: 100 })

  // A link to one perspective (from a theme page) lands on it once the story and its perspectives are there.
  const loaded = Boolean(story.row) && perspectives.data !== undefined
  useEffect(() => {
    if (!location.hash || !loaded) return
    const target = document.getElementById(decodeURIComponent(location.hash.slice(1)))
    if (target) {
      target.scrollIntoView()
      target.focus({ preventScroll: true })
    }
  }, [location.hash, loaded])

  if (story.row === null) return <NotFound />
  if (!story.row) {
    return (
      <div className="wrap page-head">
        <QueryState loading={story.isLoading} error={story.error} onRetry={() => void story.refetch()} />
      </div>
    )
  }
  const s = story.row
  const title = pickText(s.title, locale)
  const rows = perspectives.data ?? []
  const allMedia = media.data ?? []
  const people = rows.filter((row, i) => rows.findIndex((r) => r.participantSlug === row.participantSlug) === i)
  const storyMedia = allMedia.filter((m) => !m.contributionId)

  return (
    <>
      <PageMeta title={title} description={pickText(s.summary, locale)} />
      <div className="wrap">
        <header className={`${styles.storyHead} enter`}>
          <p className="crumbs">
            <Link to={path(locale, 'stories')}>{t('nav.stories')}</Link>
          </p>
          <LocalizedText as="h1" value={s.title} />
          <LocalizedText as="p" className={styles.summary} value={s.summary} />
          <div className={styles.meta}>
            <span className="label">
              {dictLabel(t, 'mediaType', s.mediaType)}
              {s.storyDate ? (
                <>
                  {' · '}
                  <time dateTime={isoDate(s.storyDate)}>{formatDate(s.storyDate, locale)}</time>
                </>
              ) : null}
            </span>
            {themes.data && themes.data.length > 0 ? (
              <span className="label">
                {t('story.themes')}:{' '}
                {themes.data.map((th, i) => (
                  <span key={th.slug}>
                    {i > 0 ? ', ' : null}
                    <Link to={path(locale, 'themes', th.slug)}>
                      <LocalizedText value={th.title} />
                    </Link>
                  </span>
                ))}
              </span>
            ) : null}
          </div>
          {people.length > 0 ? (
            <ul className={styles.contributors} aria-label={t('story.correspondents')}>
              {people.map((row) => (
                <li key={row.participantSlug}>
                  <Link to={path(locale, 'people', row.participantSlug)}>
                    <span>{row.displayName}</span>
                    <Place countryCode={row.countryCode} name={row.locationName} />
                  </Link>
                </li>
              ))}
            </ul>
          ) : null}
        </header>

        <div className={styles.storyBody}>
          {s.videoId ? (
            <div className={styles.wide}>
              <VideoEmbed
                provider={s.videoProvider}
                videoId={s.videoId}
                title={title}
                posterFileId={s.thumbnailFileId}
                transcript={s.transcript}
              />
            </div>
          ) : null}
          {pick(s.about, locale) ? (
            <section aria-labelledby="story-about">
              <h2 id="story-about" className={styles.sectionTitle}>
                {t('story.about')}
              </h2>
              <Markdown value={s.about} />
            </section>
          ) : null}
          {pick(s.body, locale) ? (
            <section aria-labelledby="story-body">
              <h2 id="story-body" className={styles.sectionTitle}>
                {t('story.article')}
              </h2>
              <Markdown value={s.body} />
            </section>
          ) : null}
          {!s.videoId && pick(s.transcript, locale) ? <Markdown value={s.transcript} /> : null}
          {storyMedia.length > 0 ? (
            <section aria-labelledby="story-photos" className={styles.wide}>
              <h2 id="story-photos" className={styles.sectionTitle}>
                {t('story.photos')}
              </h2>
              <MediaList media={storyMedia} label={t('story.photos')} />
            </section>
          ) : null}
          <QueryState loading={perspectives.isLoading} error={perspectives.error} onRetry={() => void perspectives.refetch()} />
          {rows.length > 0 ? (
            <section aria-labelledby="story-perspectives">
              <h2 id="story-perspectives" className={styles.sectionTitle}>
                {t('story.perspectives')}
              </h2>
              <div className={styles.stack}>
                {rows.map((row) => (
                  <PerspectiveFull
                    key={row.contributionId}
                    row={row}
                    media={allMedia.filter((m) => m.contributionId === row.contributionId)}
                    posterFileId={s.thumbnailFileId}
                  />
                ))}
              </div>
            </section>
          ) : null}
        </div>
      </div>
      <ReflectionBlock surprised={s.reflectionSurprised} assumed={s.reflectionAssumed} learned={s.reflectionLearned} />
    </>
  )
}
