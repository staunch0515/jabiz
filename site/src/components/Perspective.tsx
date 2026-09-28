import { Link } from 'react-router'
import type { LocalizedText as Text, QueryRow } from '../api/public-queries'
import { fileUrl } from '../api/client'
import { useLocale, useT } from '../i18n/locale'
import { pick, pickText } from '../lib/localized'
import { path } from '../lib/paths'
import { LocalizedText } from './LocalizedText'
import { Markdown } from './Markdown'
import { Place } from './Place'
import styles from './Perspective.module.css'
import { ResponsiveImage } from './ResponsiveImage'
import { Transcript, VideoEmbed } from './VideoEmbed'

/** The fields every perspective row has, from theme_perspectives and story_perspectives. */
export interface PerspectiveRow {
  contributionId: string | null
  participantSlug: string | null
  displayName: string | null
  countryCode: string | null
  locationName: Text | null
  portraitFileId: string | null
  portraitAlt: Text | null
  heading: Text | null
  text: Text | null
  videoProvider: string | null
  videoId: string | null
  transcript: Text | null
  audioFileId: string | null
}

/** Who speaks, before anything else: the person, then the place they speak from (design section 0). */
export function PersonHead({ row }: { row: PerspectiveRow }) {
  const locale = useLocale()
  return (
    <div className={styles.who}>
      <ResponsiveImage
        className={styles.avatar}
        fileId={row.portraitFileId}
        alt=""
        ratio="round"
        sizes="52px"
        placeholder={row.displayName ?? ''}
      />
      <div>
        <span className={styles.name}>
          <Link to={path(locale, 'people', row.participantSlug)}>{row.displayName}</Link>
        </span>
        <Place countryCode={row.countryCode} name={row.locationName} />
      </div>
    </div>
  )
}

function plain(markdown: string): string {
  return markdown.replace(/[#>*_`[\]()!-]/g, '').replace(/\s+/g, ' ').trim()
}

/**
 * A perspective in a comparison (theme page, brief section 8): the person, their heading, the start of their text,
 * and the way to the story it belongs to.
 */
export function PerspectiveSummary({
  row,
  storySlug,
  headingLevel = 3,
}: {
  row: PerspectiveRow
  storySlug: string | null
  headingLevel?: 2 | 3
}) {
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  const locale = useLocale()
  const t = useT()
  const text = pick(row.text, locale) ?? pick(row.transcript, locale)
  const headingId = `p-${row.contributionId}`
  return (
    <article className={styles.perspective} aria-labelledby={headingId}>
      <PersonHead row={row} />
      <Heading className={styles.heading} id={headingId}>
        {pick(row.heading, locale) ? <LocalizedText value={row.heading} /> : row.displayName}
      </Heading>
      {text ? (
        <p className={styles.excerpt} lang={text.fallback ? text.lang : undefined}>
          {plain(text.text)}
        </p>
      ) : null}
      {row.videoId ? <p className="label">{t('dict.mediaType.VIDEO')}</p> : null}
      <Link className={styles.more} to={`${path(locale, 'stories', storySlug)}#${headingId}`}>
        {t('theme.readInStory')}
        <span aria-hidden="true">&nbsp;→</span>
      </Link>
    </article>
  )
}

export type MediaRow = QueryRow<'culture.public.story_media'>

/** A photo with its alt text, caption and credit. */
export function Photo({ media }: { media: MediaRow }) {
  const locale = useLocale()
  return (
    <figure>
      <ResponsiveImage
        fileId={media.imageFileId}
        alt={pickText(media.alt, locale)}
        ratio="square"
        sizes="(max-width: 640px) 100vw, 33vw"
      />
      {media.caption || media.credit ? (
        <figcaption>
          <LocalizedText value={media.caption} />
          {media.credit ? <span> · {media.credit}</span> : null}
        </figcaption>
      ) : null}
    </figure>
  )
}

/** Photos and audio of a story or of one perspective. */
export function MediaList({ media, label }: { media: MediaRow[]; label: string }) {
  const photos = media.filter((m) => m.kind === 'PHOTO' && m.imageFileId)
  const audio = media.filter((m) => m.kind === 'AUDIO' && m.audioFileId)
  if (photos.length === 0 && audio.length === 0) return null
  return (
    <div className={styles.media}>
      {photos.length > 0 ? (
        <ul className={styles.photos} aria-label={label}>
          {photos.map((m) => (
            <li key={m.mediaItemId}>
              <Photo media={m} />
            </li>
          ))}
        </ul>
      ) : null}
      {audio.map((m) => (
        <figure key={m.mediaItemId} className={styles.figure}>
          <audio className={styles.audio} controls preload="none" src={fileUrl(m.audioFileId!)} />
          {m.caption ? (
            <figcaption>
              <LocalizedText value={m.caption} />
            </figcaption>
          ) : null}
        </figure>
      ))}
    </div>
  )
}

/**
 * A perspective in full, on its story's page (brief section 10): the person, their text, their video (with the
 * story's thumbnail as poster, design section 16), their audio with its transcript, and their photos.
 */
export function PerspectiveFull({
  row,
  media,
  posterFileId,
}: {
  row: PerspectiveRow
  media: MediaRow[]
  posterFileId: string | null
}) {
  const locale = useLocale()
  const t = useT()
  const headingId = `p-${row.contributionId}`
  const title = pickText(row.heading, locale) || row.displayName || ''
  return (
    <article className={styles.full} aria-labelledby={headingId}>
      <PersonHead row={row} />
      <h3 className={styles.heading} id={headingId} tabIndex={-1}>
        {pick(row.heading, locale) ? <LocalizedText value={row.heading} /> : row.displayName}
      </h3>
      <VideoEmbed
        provider={row.videoProvider}
        videoId={row.videoId}
        title={title}
        posterFileId={posterFileId ?? row.portraitFileId}
        transcript={row.videoId ? row.transcript : null}
      />
      <Markdown value={row.text} />
      {row.audioFileId ? (
        <figure className={styles.figure}>
          <audio className={styles.audio} controls preload="none" src={fileUrl(row.audioFileId)} />
          {row.transcript && !row.videoId ? <Transcript value={row.transcript} /> : null}
        </figure>
      ) : null}
      <MediaList media={media} label={t('story.photos')} />
    </article>
  )
}
