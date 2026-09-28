import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { QueryRow } from '../api/public-queries'
import { usePublicQuery } from '../api/hooks'
import { LocalizedText } from '../components/LocalizedText'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { useLocale, useT } from '../i18n/locale'
import { flag } from '../lib/flags'
import { dictLabel } from '../lib/labels'
import { pickText } from '../lib/localized'
import { extent, HEIGHT, landPath, projectionFor, spread, WIDTH, type MapPlace } from '../lib/map'
import { path } from '../lib/paths'
import styles from './Map.module.css'

type Location = QueryRow<'culture.public.locations'>

/**
 * Markers are moved apart until they are this far from each other on the screen (in pixels), so that each can be
 * pressed on its own: the buttons are 44 px (site CLAUDE.md section 3).
 */
const MARKER_SPACING = 46

/** The map's width on the screen, followed as the window changes; the drawing's own width until it is measured. */
function useWidth(ref: React.RefObject<HTMLElement | null>): number {
  const [width, setWidth] = useState(WIDTH)
  useEffect(() => {
    const element = ref.current
    if (!element || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(([entry]) => {
      if (entry.contentRect.width > 0) setWidth(Math.round(entry.contentRect.width))
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [ref])
  return width
}

function useSummary() {
  const t = useT()
  const locale = useLocale()
  return (place: Location) =>
    t('map.summary', {
      place: pickText(place.name, locale),
      people: t('count.people', { count: place.participantCount ?? 0 }),
      stories: t('count.stories', { count: place.storyCount ?? 0 }),
    })
}

interface Marker {
  place: Location
  /** Where the place is. */
  at: { x: number; y: number }
  /** Where its button is, moved away from its neighbours. */
  button: { x: number; y: number }
}

/**
 * The world map (brief section 13, design section 9.5): the outline of the land from the bundled world atlas, drawn
 * here, with no tiles or requests to a map service. Every place is a button; the list next to it offers the same.
 */
function WorldMap({ places, selected, onSelect }: { places: Location[]; selected: string | null; onSelect: (slug: string) => void }) {
  const t = useT()
  const summary = useSummary()
  const frame = useRef<HTMLDivElement>(null)
  const width = useWidth(frame)
  const { land, markers } = useMemo(() => {
    const located = places.filter(
      (p): p is Location & { slug: string; latitude: number; longitude: number } =>
        p.slug !== null && p.latitude !== null && p.longitude !== null,
    )
    const projection = projectionFor(extent(located.map((p): MapPlace => p)))
    const at = located.map((p) => {
      const [x, y] = projection([p.longitude, p.latitude]) ?? [0, 0]
      return { x, y }
    })
    const buttons = spread(at, (MARKER_SPACING * WIDTH) / width)
    return {
      land: landPath(projection),
      markers: located.map((place, i): Marker => ({ place, at: at[i], button: buttons[i] })),
    }
  }, [places, width])

  return (
    <div className={styles.map} ref={frame}>
      <svg viewBox={`0 0 ${WIDTH} ${HEIGHT}`} aria-hidden="true" focusable="false" className={styles.svg}>
        <path d={land} className={styles.land} />
        {markers.map(({ place, at, button }) => (
          <g key={place.slug} className={place.slug === selected ? styles.chosen : undefined}>
            {Math.hypot(button.x - at.x, button.y - at.y) > 1 ? (
              <line x1={at.x} y1={at.y} x2={button.x} y2={button.y} className={styles.leader} />
            ) : null}
            <circle cx={at.x} cy={at.y} r={3} className={styles.spot} />
            {/* On a phone the markers are too close to press: the list below is the way in, the map a picture. */}
            <circle cx={button.x} cy={button.y} r={9} className={styles.dot} />
          </g>
        ))}
      </svg>
      <ul className={styles.markers} aria-label={t('map.markers')}>
        {markers.map(({ place, button }) => (
          <li key={place.slug} style={{ left: `${(button.x / WIDTH) * 100}%`, top: `${(button.y / HEIGHT) * 100}%` }}>
            <button
              type="button"
              className={styles.marker}
              aria-pressed={place.slug === selected}
              aria-label={summary(place)}
              onClick={() => onSelect(place.slug ?? '')}
            >
              <span className={styles.name} aria-hidden="true">
                <LocalizedText value={place.name} />
              </span>
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

/** What is published from a place: its people, their themes, stories, videos and photographs (brief section 13). */
function PlacePanel({ place, headingRef }: { place: Location; headingRef: React.RefObject<HTMLHeadingElement | null> }) {
  const t = useT()
  const locale = useLocale()
  const slug = place.slug ?? ''
  const people = usePublicQuery('culture.public.people', { location: [slug] }, { limit: 100 })
  const themes = usePublicQuery('culture.public.location_themes', { location: slug }, { limit: 100 })
  const stories = usePublicQuery('culture.public.stories', { location: [slug] }, { limit: 100 })
  const media = usePublicQuery('culture.public.location_media', { location: slug }, { limit: 100 })
  const videos = (media.data ?? []).filter((m) => m.kind === 'VIDEO')
  const photos = (media.data ?? []).filter((m) => m.kind === 'PHOTO')
  const name = pickText(place.name, locale)
  const loading = people.isLoading || themes.isLoading || stories.isLoading || media.isLoading
  const error = people.error ?? themes.error ?? stories.error ?? media.error
  const empty = !loading && !error && (people.data ?? []).length === 0 && (stories.data ?? []).length === 0

  return (
    <>
      <h2 ref={headingRef} tabIndex={-1} className={styles.panelHeading}>
        {flag(place.countryCode) ? <span aria-hidden="true">{flag(place.countryCode)} </span> : null}
        <LocalizedText value={place.name} />
      </h2>
      {place.placeLabel ? <LocalizedText as="p" className="label" value={place.placeLabel} /> : null}
      <QueryState
        loading={loading}
        error={error}
        onRetry={() => [people, themes, stories, media].forEach((q) => void q.refetch())}
      />
      {empty ? <p>{t('map.nothing')}</p> : null}

      {(people.data ?? []).length > 0 ? (
        <section className={styles.part} aria-labelledby="map-people">
          <h3 id="map-people">{t('map.people')}</h3>
          <ul className={styles.people}>
            {(people.data ?? []).map((person) => (
              <li key={person.slug}>
                <Link to={path(locale, 'people', person.slug)}>
                  <ResponsiveImage
                    fileId={person.portraitFileId}
                    alt=""
                    ratio="round"
                    sizes="48px"
                    placeholder={person.displayName ?? ''}
                    className={styles.avatar}
                  />
                  <span>{person.displayName}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {(themes.data ?? []).length > 0 ? (
        <section className={styles.part} aria-labelledby="map-themes">
          <h3 id="map-themes">{t('map.themes')}</h3>
          <ul className={styles.links}>
            {(themes.data ?? []).map((theme) => (
              <li key={theme.slug}>
                <Link to={path(locale, 'themes', theme.slug)}>
                  <span aria-hidden="true">{theme.icon} </span>
                  <LocalizedText value={theme.title} />
                </Link>{' '}
                <span className="label">{t('count.stories', { count: theme.storyCount ?? 0 })}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {(stories.data ?? []).length > 0 ? (
        <section className={styles.part} aria-labelledby="map-stories">
          <h3 id="map-stories">{t('map.stories')}</h3>
          <ul className={styles.links}>
            {(stories.data ?? []).map((story) => (
              <li key={story.slug}>
                <Link to={path(locale, 'stories', story.slug)}>
                  <LocalizedText value={story.title} />
                </Link>{' '}
                <span className="label">{dictLabel(t, 'mediaType', story.mediaType)}</span>
              </li>
            ))}
          </ul>
          <p>
            <Link className={styles.more} to={`${path(locale, 'stories')}?place=${encodeURIComponent(slug)}`}>
              {t('map.allStories', { place: name })} →
            </Link>
          </p>
        </section>
      ) : null}

      {videos.length > 0 ? (
        <section className={styles.part} aria-labelledby="map-videos">
          <h3 id="map-videos">{t('map.videos')}</h3>
          <ul className={styles.tiles}>
            {videos.map((video) => (
              <li key={video.itemId}>
                <Link to={path(locale, 'stories', video.storySlug)} className={styles.tile}>
                  <ResponsiveImage fileId={video.imageFileId} alt="" ratio="wide" sizes="(max-width: 860px) 50vw, 180px" />
                  <span>
                    <span aria-hidden="true">▶ </span>
                    <LocalizedText value={video.storyTitle} />
                  </span>
                  {video.displayName ? <span className="label">{t('map.by', { name: video.displayName })}</span> : null}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      {photos.length > 0 ? (
        <section className={styles.part} aria-labelledby="map-photos">
          <h3 id="map-photos">{t('map.photos')}</h3>
          <ul className={styles.tiles}>
            {photos.map((photo) => (
              <li key={photo.itemId}>
                <Link to={path(locale, 'stories', photo.storySlug)} className={styles.tile}>
                  <ResponsiveImage
                    fileId={photo.imageFileId}
                    alt={pickText(photo.alt, locale)}
                    ratio="square"
                    sizes="(max-width: 860px) 50vw, 180px"
                  />
                  {photo.displayName ? <span className="label">{t('map.by', { name: photo.displayName })}</span> : null}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  )
}

/**
 * The map page (design section 9.5): a secondary way in, reached from PEOPLE, the footer and the home page's places.
 * The chosen place is in the address (`?place=`), so that it can be shared.
 */
export function Map() {
  const t = useT()
  const summary = useSummary()
  const [search, setSearch] = useSearchParams()
  const places = usePublicQuery('culture.public.locations', {}, { limit: 100 })
  const heading = useRef<HTMLHeadingElement>(null)
  const all = places.data ?? []
  const chosen = all.find((p) => p.slug !== null && p.slug === search.get('place')) ?? null

  // From the list, the panel is further down the page: the focus goes there once it shows the place. From the map
  // the panel is next to it: the focus stays on the map.
  const focusPanel = useRef<string | null>(null)
  useEffect(() => {
    if (chosen?.slug && focusPanel.current === chosen.slug) {
      focusPanel.current = null
      heading.current?.focus()
    }
  }, [chosen?.slug])

  const select = (slug: string, fromList: boolean) => {
    focusPanel.current = fromList ? slug : null
    setSearch({ place: slug }, { replace: true, preventScrollReset: true })
  }

  return (
    <div className="wrap">
      <PageMeta title={t('map.title')} description={t('map.intro')} />
      <div className="page-head">
        <h1>{t('map.title')}</h1>
        <p className="intro">{t('map.intro')}</p>
      </div>
      <QueryState loading={places.isLoading} error={places.error} onRetry={() => void places.refetch()} />
      {/* The map, the list and the panel show together once the places are in: nothing moves as they arrive. */}
      {places.isLoading ? null : (
        <div className={styles.layout}>
          <div className={styles.main}>
            <WorldMap places={all} selected={chosen?.slug ?? null} onSelect={(slug) => select(slug, false)} />
            <h2 className={styles.listHeading}>{t('map.list')}</h2>
            <ul className={styles.list}>
              {all.map((place) => (
                <li key={place.slug}>
                  <button
                    type="button"
                    aria-pressed={place.slug === chosen?.slug}
                    onClick={() => select(place.slug ?? '', true)}
                  >
                    <span className={styles.placeName}>
                      {flag(place.countryCode) ? <span aria-hidden="true">{flag(place.countryCode)} </span> : null}
                      <LocalizedText value={place.name} />
                    </span>
                    <span className="label">
                      {t('count.people', { count: place.participantCount ?? 0 })} ·{' '}
                      {t('count.stories', { count: place.storyCount ?? 0 })}
                      {place.latitude === null || place.longitude === null ? ` · ${t('map.notOnMap')}` : ''}
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </div>
          <aside className={styles.panel} aria-label={chosen ? summary(chosen) : t('map.list')}>
            <p className="visually-hidden" role="status">
              {chosen ? summary(chosen) : ''}
            </p>
            {chosen ? <PlacePanel key={chosen.slug} place={chosen} headingRef={heading} /> : <p>{t('map.choose')}</p>}
          </aside>
        </div>
      )}
    </div>
  )
}
