import { Link } from 'react-router'
import type { QueryRow } from '../api/public-queries'
import { useLocale, useT } from '../i18n/locale'
import { path } from '../lib/paths'
import styles from './Cards.module.css'
import { LocalizedText } from './LocalizedText'

/** Themes as a list of questions (brief section 8): icon, title, the question, how many places answer it. */
export function ThemeList({ themes }: { themes: QueryRow<'culture.public.themes'>[] }) {
  const locale = useLocale()
  const t = useT()
  return (
    <ul className={styles.themes}>
      {themes.map((theme) => (
        <li key={theme.slug}>
          <Link to={path(locale, 'themes', theme.slug)}>
            <span className={styles.icon} aria-hidden="true">
              {theme.icon}
            </span>
            <LocalizedText className={styles.title} value={theme.title} />
            <LocalizedText className={styles.question} value={theme.question} />
            <span className={`${styles.count} label`}>
              {t('count.places', { count: theme.locationCount ?? 0 })} · {t('count.stories', { count: theme.storyCount ?? 0 })}
            </span>
          </Link>
        </li>
      ))}
    </ul>
  )
}
