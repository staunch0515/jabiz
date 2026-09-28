import type { LocalizedText } from '../api/public-queries'
import { useT } from '../i18n/locale'
import { Markdown } from './Markdown'
import styles from './ReflectionBlock.module.css'

interface Props {
  surprised: LocalizedText | null
  assumed: LocalizedText | null
  learned: LocalizedText | null
}

/**
 * The three questions every story ends with (brief section 10): what surprised us, what we assumed, what we learned.
 * A block of its own, in reversed colours, because the project is meant to be more than entertainment.
 */
export function ReflectionBlock({ surprised, assumed, learned }: Props) {
  const t = useT()
  const answers = [
    ['surprised', surprised],
    ['assumed', assumed],
    ['learned', learned],
  ] as const
  const present = answers.filter(([, value]) => value && Object.values(value).some((v) => v && v.trim() !== ''))
  if (present.length === 0) return null
  return (
    <section className={styles.reflection} aria-labelledby="reflection">
      <div className="wrap">
        <p className="label">{t('story.afterWatching')}</p>
        <h2 id="reflection">{t('story.reflection')}</h2>
        <div className={styles.questions}>
          {present.map(([key, value]) => (
            <div key={key}>
              <h3>{t(`story.${key}`)}</h3>
              <Markdown value={value} />
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
