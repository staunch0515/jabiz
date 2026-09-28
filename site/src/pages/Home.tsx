import { Link } from 'react-router'
import { useSiteBlocks } from '../api/blocks'
import { usePublicQuery } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { LocalizedText } from '../components/LocalizedText'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { PersonCard } from '../components/PersonCard'
import { Place } from '../components/Place'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { StoryCard } from '../components/StoryCard'
import { ThemeList } from '../components/ThemeList'
import { useLocale, useT } from '../i18n/locale'
import { pick, pickText, type Picked } from '../lib/localized'
import { path } from '../lib/paths'
import styles from './Pages.module.css'

const BLOCKS = [
  'site.tagline',
  'home.hero.title',
  'home.hero.subtitle',
  'home.cta.explore',
  'home.cta.method',
  'home.question',
  'home.answer',
  'home.intro',
]

/** The English title preset by the migrations (V2__culture_site_blocks.sql). */
const PRESET_TITLE = { text: 'CULTURE, UNFILTERED', lang: 'en' } as const

/** "CULTURE, UNFILTERED": the part after the first comma is set in italics, on its own line. */
function HeroTitle({ picked }: { picked: Picked | null }) {
  if (!picked) return <h1 lang="en">Culture, Unfiltered</h1>
  const comma = picked.text.indexOf(',')
  const lang = picked.fallback ? picked.lang : undefined
  if (comma < 0) return <h1 lang={lang}>{picked.text}</h1>
  return (
    <h1 lang={lang}>
      {picked.text.slice(0, comma + 1)} <span>{picked.text.slice(comma + 1).trim()}</span>
    </h1>
  )
}

/** "Probably not. That's why …": the first sentence is highlighted, the rest follows. */
function Answer({ picked }: { picked: Picked | null }) {
  if (!picked) return null
  const match = /^(.+?[.!?。！？])\s*(.*)$/s.exec(picked.text)
  return (
    <p className={styles.answer} lang={picked.fallback ? picked.lang : undefined}>
      {match ? (
        <>
          <span className="mark">{match[1]}</span> {match[2]}
        </>
      ) : (
        picked.text
      )}
    </p>
  )
}

/**
 * The home page (brief section 4): the project's idea first, then its people, stories and questions. Every text comes
 * from site blocks and every place, person, story and theme from the data (design section 0).
 */
export function Home() {
  const t = useT()
  const locale = useLocale()
  const { blocks, isLoading: blocksLoading } = useSiteBlocks(BLOCKS)
  const places = usePublicQuery('culture.public.locations', {}, { limit: 100 })
  const people = usePublicQuery('culture.public.people', {}, { limit: 100 })
  const featured = usePublicQuery('culture.public.stories', { featured: true }, { limit: 3 })
  const latest = usePublicQuery('culture.public.stories', {}, { limit: 3 })
  const themes = usePublicQuery('culture.public.themes', {}, { limit: 100 })

  const stories = featured.data && featured.data.length > 0 ? featured.data : latest.data
  const collage = (people.data ?? []).slice(0, 6)

  // The title paints at once (the largest text of the first screen: what a visitor sees first). Everything under it
  // shows once the texts, places and people are in, all at once: filled in one by one, each part would push the next
  // down while the visitor reads (layout shift). The sections further down are off the first screen.
  const ready = !blocksLoading && !places.isLoading && !people.isLoading
  // Until the block is in, the title as the site's first release wrote it: usually the block says the same, and the
  // heading then stays as it is (a changed heading is painted, and measured, again).
  const title = <HeroTitle picked={blocksLoading ? { ...PRESET_TITLE, fallback: locale !== 'en' } : pick(blocks['home.hero.title'], locale)} />
  return (
    <>
      <PageMeta description={pickText(blocks['site.tagline'], locale)} />
      <div className={`wrap ${styles.hero}`}>
        <div className={styles.heroText}>
          <p className="label">{t('home.eyebrow')}</p>
          {title}
          {ready ? (
            <>
              <LocalizedText as="p" className={styles.subtitle} value={blocks['home.hero.subtitle']} />
              {places.data && places.data.length > 0 ? (
                <ul className={`${styles.places} label`} aria-label={t('home.places')}>
                  {places.data.map((place) => (
                    <li key={place.slug}>
                      <Link to={`${path(locale, 'stories')}?place=${encodeURIComponent(place.slug ?? '')}`}>
                        <Place countryCode={place.countryCode} name={place.name} className="" />
                      </Link>
                    </li>
                  ))}
                </ul>
              ) : null}
              <div className={styles.ctas}>
                <Link className="btn btn-primary" to={path(locale, 'stories')}>
                  <LocalizedText value={blocks['home.cta.explore']} />
                  <span aria-hidden="true">→</span>
                </Link>
                <Link className="btn" to={path(locale, 'method')}>
                  <LocalizedText value={blocks['home.cta.method']} />
                </Link>
              </div>
            </>
          ) : (
            <QueryState loading error={null} />
          )}
        </div>
        {ready && collage.length > 0 ? (
          <ul className={styles.collage} aria-label={t('home.people')}>
            {collage.map((person) => (
              <li key={person.slug}>
                <Link to={path(locale, 'people', person.slug)}>
                  <ResponsiveImage
                    fileId={person.portraitFileId}
                    alt=""
                    ratio="portrait"
                    sizes="(max-width: 860px) 33vw, 16vw"
                    placeholder={person.displayName ?? ''}
                    eager
                  />
                  <span className="label">
                    <b>{person.displayName}</b>
                    <Place countryCode={person.countryCode} name={person.locationName} className="" />
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        ) : null}
      </div>

      {ready ? (
        <>
          <section className="section" aria-labelledby="home-question">
            <div className={`wrap ${styles.question}`}>
              <p className="label">{t('home.questionEyebrow')}</p>
              <LocalizedText as="h2" id="home-question" value={blocks['home.question']} />
              <Answer picked={pick(blocks['home.answer'], locale)} />
              <div className={styles.questionBody}>
                <Markdown value={blocks['home.intro']} />
              </div>
            </div>
          </section>

          <section className="section" aria-labelledby="home-stories">
            <div className="wrap">
              <div className="section-head">
                <h2 id="home-stories">{t('home.featured')}</h2>
                <Link className="btn" to={path(locale, 'stories')}>
                  {t('home.allStories')}
                </Link>
              </div>
              <QueryState loading={featured.isLoading || latest.isLoading} error={featured.error ?? latest.error} />
              <ul className={cards.grid3}>
                {(stories ?? []).map((story) => (
                  <li key={story.slug}>
                    <StoryCard story={story} />
                  </li>
                ))}
              </ul>
            </div>
          </section>

          <section className="section" aria-labelledby="home-themes">
            <div className="wrap">
              <div className="section-head">
                <h2 id="home-themes">{t('home.themes')}</h2>
                <Link className="btn" to={path(locale, 'themes')}>
                  {t('home.allThemes')}
                </Link>
              </div>
              <QueryState loading={themes.isLoading} error={themes.error} onRetry={() => void themes.refetch()} />
              <ThemeList themes={themes.data ?? []} />
            </div>
          </section>

          <section className="section" aria-labelledby="home-people">
            <div className="wrap">
              <div className="section-head">
                <h2 id="home-people">{t('home.people')}</h2>
                <Link className="btn" to={path(locale, 'people')}>
                  {t('home.allPeople')}
                </Link>
              </div>
              <QueryState loading={people.isLoading} error={people.error} onRetry={() => void people.refetch()} />
              <ul className={cards.grid4}>
                {(people.data ?? []).map((person) => (
                  <li key={person.slug}>
                    <PersonCard person={person} />
                  </li>
                ))}
              </ul>
            </div>
          </section>
        </>
      ) : null}
    </>
  )
}
