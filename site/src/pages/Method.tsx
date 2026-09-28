import { useSiteBlocks } from '../api/blocks'
import { Markdown } from '../components/Markdown'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { useT } from '../i18n/locale'
import styles from './Pages.module.css'

const SECTIONS = [
  ['ethnography', 'method.ethnography'],
  ['approach', 'method.approach'],
  ['reflexivity', 'method.reflexivity'],
  ['emicEtic', 'method.emic-etic'],
  ['representation', 'method.representation'],
] as const

const PRINCIPLES = ['consent', 'context', 'respect', 'reflexivity', 'responsible-representation'] as const

/**
 * OUR METHOD (brief section 11): the sections' headings are part of the interface, their texts are site blocks that
 * editors change in the admin.
 */
export function Method() {
  const t = useT()
  const keys = [...SECTIONS.map(([, key]) => key), ...PRINCIPLES.map((p) => `method.principle.${p}`)]
  const { blocks, isLoading, error, refetch } = useSiteBlocks(keys)
  return (
    <div className="wrap">
      <PageMeta title={t('method.title')} description={t('method.intro')} />
      <div className="page-head">
        <h1>{t('method.title')}</h1>
        <p className="intro">{t('method.intro')}</p>
      </div>
      <QueryState loading={isLoading} error={error} onRetry={() => void refetch()} />
      <div className={styles.methodSections}>
        {SECTIONS.map(([heading, key]) =>
          blocks[key] ? (
            <section key={key} className={styles.twoCol} aria-labelledby={`method-${heading}`}>
              <h2 id={`method-${heading}`}>{t(`method.${heading}`)}</h2>
              <Markdown value={blocks[key]} />
            </section>
          ) : null,
        )}
        <section aria-labelledby="method-principles">
          <div className="section-head">
            <h2 id="method-principles">{t('method.principles')}</h2>
          </div>
          <ul className={styles.principles}>
            {PRINCIPLES.map((p) => (
              <li key={p}>
                <h3>{t(`method.principle.${p}`)}</h3>
                <Markdown value={blocks[`method.principle.${p}`]} />
              </li>
            ))}
          </ul>
        </section>
      </div>
    </div>
  )
}
