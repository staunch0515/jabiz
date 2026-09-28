import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { QueryRow } from '../api/public-queries'
import { usePublicQuery } from '../api/hooks'
import { LocalizedText } from '../components/LocalizedText'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { useLocale, useT } from '../i18n/locale'
import { path, type Section } from '../lib/paths'
import { MAX_WORDS, searchable } from '../lib/search'
import styles from './Search.module.css'

type Hit = QueryRow<'culture.public.search'>

/** The kinds of results, in the order the page shows them, and where each one's page is. */
const KINDS: { kind: string; section: Section }[] = [
  { kind: 'STORY', section: 'stories' },
  { kind: 'PERSON', section: 'people' },
  { kind: 'THEME', section: 'themes' },
  { kind: 'RESOURCE', section: 'resources' },
]

function Result({ hit, section }: { hit: Hit; section: Section }) {
  const locale = useLocale()
  const withImage = hit.kind === 'STORY' || hit.kind === 'PERSON'
  return (
    <li className={styles.hit}>
      {withImage ? (
        <ResponsiveImage
          fileId={hit.imageFileId}
          alt=""
          ratio={hit.kind === 'PERSON' ? 'round' : 'wide'}
          sizes="120px"
          placeholder={hit.name ?? ''}
          className={styles.image}
        />
      ) : null}
      <div className={styles.text}>
        <h3>
          <Link to={path(locale, section, hit.slug)}>
            {hit.kind === 'PERSON' ? hit.name : <LocalizedText value={hit.title} />}
          </Link>
        </h3>
        <LocalizedText as="p" value={hit.summary} />
      </div>
    </li>
  )
}

/**
 * Search (brief section 14): stories, people, themes and resources whose texts in any language contain the words.
 * The words are in the address (`?q=`), so that a search can be shared and the back button returns to it.
 */
export function Search() {
  const t = useT()
  const locale = useLocale()
  const [search, setSearch] = useSearchParams()
  const q = (search.get('q') ?? '').trim()
  const [draft, setDraft] = useState(q)
  const [asked, setAsked] = useState(q)
  if (asked !== q) {
    // Back and forward change the words in the address: the field follows.
    setAsked(q)
    setDraft(q)
  }
  const ready = searchable(q)
  const hits = usePublicQuery('culture.public.search', { q }, { limit: 100 }, ready)
  const shown = ready ? hits : null

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const words = draft.trim()
    setSearch(words ? { q: words } : {}, { preventScrollReset: true })
  }

  const count = shown?.data?.length
  let status = ''
  if (q && !ready) status = t('search.tooShort')
  else if (count !== undefined) status = count === 0 ? t('search.none', { q }) : t('search.results', { count, q })

  return (
    <div className="wrap">
      <PageMeta title={q ? `${t('search.title')}: ${q}` : t('search.title')} description={t('search.intro')} />
      <div className="page-head">
        <h1>{t('search.title')}</h1>
        <p className="intro">{t('search.intro')}</p>
      </div>
      <form role="search" className={styles.form} onSubmit={submit} aria-label={t('search.title')}>
        <label htmlFor="search-words" className="label">
          {t('search.label')}
        </label>
        <div className={styles.row}>
          <input
            id="search-words"
            type="search"
            name="q"
            value={draft}
            maxLength={MAX_WORDS}
            autoComplete="off"
            enterKeyHint="search"
            lang={locale}
            onChange={(event) => setDraft(event.target.value)}
          />
          <button type="submit" className="btn btn-primary">
            {t('search.submit')}
          </button>
        </div>
      </form>
      <p className={`label ${styles.status}`} role="status">
        {status}
      </p>
      {shown ? <QueryState loading={shown.isLoading} error={shown.error} onRetry={() => void shown.refetch()} /> : null}
      {count === 0 ? (
        <p>
          <Link to={path(locale, 'stories')}>{t('home.allStories')} →</Link>
        </p>
      ) : null}
      <div className={styles.groups}>
        {KINDS.map(({ kind, section }) => {
          const rows = (shown?.data ?? []).filter((hit) => hit.kind === kind)
          if (rows.length === 0) return null
          return (
            <section key={kind} aria-labelledby={`search-${kind}`}>
              <h2 id={`search-${kind}`}>
                {t(`search.kind.${kind}`)} <span className="label">{rows.length}</span>
              </h2>
              <ul className={styles.hits}>
                {rows.map((hit) => (
                  <Result key={`${hit.kind}:${hit.slug}`} hit={hit} section={section} />
                ))}
              </ul>
            </section>
          )
        })}
      </div>
    </div>
  )
}
