import { Link, useParams } from 'react-router'
import { usePublicQuery, usePublicRow } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { LocalizedText } from '../components/LocalizedText'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { Place } from '../components/Place'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { StoryCard } from '../components/StoryCard'
import { useLocale, useT } from '../i18n/locale'
import { pick, pickText } from '../lib/localized'
import { path } from '../lib/paths'
import { NotFound } from './NotFound'
import styles from './Pages.module.css'

/**
 * A participant's page (brief section 7): header, About, My perspective, their stories, the themes they contributed
 * to and their personal reflection. Languages are shown only when the participant chose to give them.
 */
export function Person() {
  const t = useT()
  const locale = useLocale()
  const slug = useParams().slug ?? ''
  const person = usePublicRow('culture.public.person', { slug })
  const stories = usePublicQuery('culture.public.person_stories', { slug }, { limit: 100 })
  const themes = usePublicQuery('culture.public.person_themes', { slug }, { limit: 100 })

  if (person.row === null) return <NotFound />
  if (!person.row) {
    return (
      <div className="wrap page-head">
        <QueryState loading={person.isLoading} error={person.error} onRetry={() => void person.refetch()} />
      </div>
    )
  }
  const p = person.row
  const interests = (pick(p.interests, locale)?.text ?? '')
    .split(/[,，、]/)
    .map((s) => s.trim())
    .filter(Boolean)
  const interestsLang = pick(p.interests, locale)
  return (
    <>
      <PageMeta title={p.displayName ?? ''} description={pickText(p.shortBio, locale)} />
      <div className={`wrap ${styles.personHead} enter`}>
        <ResponsiveImage
          fileId={p.portraitFileId}
          alt={pickText(p.portraitAlt, locale)}
          ratio="portrait"
          sizes="(max-width: 760px) 320px, 33vw"
          placeholder={p.displayName ?? ''}
          eager
        />
        <div className={styles.personText}>
          <p className="crumbs">
            <Link to={path(locale, 'people')}>{t('nav.people')}</Link>
          </p>
          <h1>{p.displayName}</h1>
          <Place countryCode={p.countryCode} name={p.locationName} area={p.placeLabel} />
          <LocalizedText as="p" className={styles.summary} value={p.shortBio} />
        </div>
      </div>

      {p.bio || p.interests || p.languages ? (
        <section className="section" aria-labelledby="person-about">
          <div className={`wrap ${styles.twoCol}`}>
            <h2 id="person-about">{t('person.about')}</h2>
            <div className="prose">
              <Markdown value={p.bio} />
              {interests.length > 0 || p.languages ? (
                <dl className={styles.facts}>
                  {interests.length > 0 ? (
                    <>
                      <dt className="label">{t('person.interests')}</dt>
                      <dd lang={interestsLang?.fallback ? interestsLang.lang : undefined}>{interests.join(' · ')}</dd>
                    </>
                  ) : null}
                  {p.languages ? (
                    <>
                      <dt className="label">{t('person.languages')}</dt>
                      <dd>{p.languages}</dd>
                    </>
                  ) : null}
                </dl>
              ) : null}
            </div>
          </div>
        </section>
      ) : null}

      {pick(p.perspective, locale) ? (
        <section className="section" aria-labelledby="person-perspective">
          <div className={`wrap ${styles.twoCol}`}>
            <h2 id="person-perspective">{t('person.perspective')}</h2>
            <LocalizedText as="blockquote" className={styles.quote} value={p.perspective} />
          </div>
        </section>
      ) : null}

      <section className="section" aria-labelledby="person-stories">
        <div className="wrap">
          <div className="section-head">
            <h2 id="person-stories">{t('person.stories')}</h2>
          </div>
          <QueryState loading={stories.isLoading} error={stories.error} onRetry={() => void stories.refetch()} />
          {stories.data && stories.data.length === 0 ? <p>{t('person.noStories')}</p> : null}
          <ul className={cards.grid3}>
            {(stories.data ?? []).map((story) => (
              <li key={story.slug}>
                <StoryCard story={story} />
              </li>
            ))}
          </ul>
        </div>
      </section>

      {themes.data && themes.data.length > 0 ? (
        <section className="section" aria-labelledby="person-themes">
          <div className={`wrap ${styles.twoCol}`}>
            <h2 id="person-themes">{t('person.themes')}</h2>
            <ul className={styles.tags}>
              {themes.data.map((theme) => (
                <li key={theme.slug}>
                  <Link to={path(locale, 'themes', theme.slug)}>
                    <span aria-hidden="true">{theme.icon}</span>
                    <LocalizedText value={theme.title} />
                  </Link>
                </li>
              ))}
            </ul>
          </div>
        </section>
      ) : null}

      {pick(p.reflection, locale) ? (
        <section className="section" aria-labelledby="person-reflection">
          <div className={`wrap ${styles.twoCol}`}>
            <h2 id="person-reflection">{t('person.reflection')}</h2>
            <Markdown value={p.reflection} />
          </div>
        </section>
      ) : null}
    </>
  )
}
