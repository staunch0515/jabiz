import { useSiteBlocks } from '../api/blocks'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { useLocale, useT } from '../i18n/locale'
import { pickText } from '../lib/localized'
import styles from './Pages.module.css'

/** ABOUT: the project and how we treat participants' privacy, both site blocks. */
export function About() {
  const t = useT()
  const locale = useLocale()
  const { blocks, isLoading, error, refetch } = useSiteBlocks(['about.project', 'about.privacy'])
  return (
    <div className="wrap">
      <PageMeta title={t('about.title')} description={pickText(blocks['about.project'], locale)} />
      <div className="page-head">
        <h1>{t('about.title')}</h1>
      </div>
      <QueryState loading={isLoading} error={error} onRetry={() => void refetch()} />
      <div className={styles.methodSections}>
        <section className={styles.twoCol} aria-labelledby="about-project">
          <h2 id="about-project">{t('about.project')}</h2>
          <Markdown value={blocks['about.project']} />
        </section>
        <section className={styles.twoCol} aria-labelledby="about-privacy">
          <h2 id="about-privacy">{t('about.privacy')}</h2>
          <Markdown value={blocks['about.privacy']} />
        </section>
      </div>
    </div>
  )
}
