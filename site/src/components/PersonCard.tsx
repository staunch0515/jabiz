import { Link } from 'react-router'
import type { LocalizedText as Text } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { pickText } from '../lib/localized'
import { path } from '../lib/paths'
import styles from './Cards.module.css'
import { LocalizedText } from './LocalizedText'
import { Place } from './Place'
import { ResponsiveImage } from './ResponsiveImage'

export interface PersonCardRow {
  slug: string | null
  displayName: string | null
  countryCode: string | null
  locationName: Text | null
  portraitFileId: string | null
  portraitAlt: Text | null
  shortBio: Text | null
  interests?: Text | null
}

/** A participant's card (brief section 6): portrait, name, place, one line about them, link to their page. */
export function PersonCard({ person, headingLevel = 3, eager }: { person: PersonCardRow; headingLevel?: 2 | 3; eager?: boolean }) {
  const locale = useLocale()
  const t = useT()
  const Heading = headingLevel === 2 ? 'h2' : 'h3'
  return (
    <article className={`${styles.card} ${styles.person}`}>
      <ResponsiveImage
        fileId={person.portraitFileId}
        alt={pickText(person.portraitAlt, locale)}
        ratio="portrait"
        sizes="(max-width: 640px) 50vw, (max-width: 980px) 33vw, 25vw"
        placeholder={person.displayName ?? ''}
        eager={eager}
      />
      <Place countryCode={person.countryCode} name={person.locationName} />
      <Heading>
        <Link to={path(locale, 'people', person.slug)}>
          {person.displayName}
        </Link>
      </Heading>
      <LocalizedText as="p" value={person.shortBio} />
      <span className={`${styles.more} label`} aria-hidden="true">
        {t('people.viewProfile')} →
      </span>
    </article>
  )
}
