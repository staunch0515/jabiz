import { Link } from 'react-router'
import type { LocalizedText as Text } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { formatDate, isoDate } from '../lib/dates'
import { dictLabel } from '../lib/labels'
import { pickText } from '../lib/localized'
import { path } from '../lib/paths'
import styles from './Cards.module.css'
import { LocalizedText } from './LocalizedText'
import { ResponsiveImage } from './ResponsiveImage'

export interface StoryCardRow {
  slug: string | null
  title: Text | null
  summary: Text | null
  mediaType: string | null
  storyDate: string | null
  thumbnailFileId: string | null
  thumbnailAlt: Text | null
}

/** A story in a list: 16:9 thumbnail, media type and date, title, short description (brief section 9). */
export function StoryCard({ story, headingLevel = 3 }: { story: StoryCardRow; headingLevel?: 2 | 3 }) {
  const locale = useLocale()
  const t = useT()
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  return (
    <article className={styles.card}>
      <ResponsiveImage
        fileId={story.thumbnailFileId}
        alt={pickText(story.thumbnailAlt, locale)}
        ratio="wide"
        sizes="(max-width: 860px) 100vw, 33vw"
      />
      <p className="label">
        {dictLabel(t, 'mediaType', story.mediaType)}
        {story.storyDate ? (
          <>
            {' · '}
            <time dateTime={isoDate(story.storyDate)}>{formatDate(story.storyDate, locale)}</time>
          </>
        ) : null}
      </p>
      <Heading>
        <Link to={path(locale, 'stories', story.slug)}>
          <LocalizedText value={story.title} />
        </Link>
      </Heading>
      <LocalizedText as="p" value={story.summary} />
    </article>
  )
}
