import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import type { QueryRow } from '../api/public-queries'
import { useQueries } from '@tanstack/react-query'
import { fetchPublic } from '../api/client'
import { LocalizedText } from '../components/LocalizedText'
import { PageMeta } from '../components/PageMeta'
import { QueryState } from '../components/QueryState'
import { ResponsiveImage } from '../components/ResponsiveImage'
import { useLocale, useT } from '../i18n/locale'
import { path, type Section } from '../lib/paths'
import { MAX_WORDS, MIN_WORDS, searchable } from '../lib/search'
import styles from './Search.module.css'

type Hit = QueryRow<'culture.public.search'>

/** At most this many results of each kind; a kind that has more says so. */
const PER_KIND = 50

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
  // One request per kind: a common word cannot fill the page with stories and leave the other kinds out.
  const groups = useQueries({
    queries: KINDS.map(({ kind }) => ({
      queryKey: ['public', 'culture.public.search', { q }, kind, locale],
      queryFn: ({ signal }: { signal: AbortSignal }) =>
        fetchPublic('culture.public.search', { q }, { limit: PER_KIND, filters: [{ field: 'kind', op: 'eq', value: kind }] }, locale, signal),
      select: (page: { items: Hit[] }) => page.items,
      enabled: ready,
    })),
  })
  const loading = ready && groups.some((g) => g.isLoading)
  const error = groups.find((g) => g.error)?.error ?? null

  const submit = (event: FormEvent) => {
    event.preventDefault()
    const words = draft.trim()
    setSearch(words ? { q: words } : {}, { preventScrollReset: true })
  }

  const count = ready && !loading && !error ? groups.reduce((sum, g) => sum + (g.data?.length ?? 0), 0) : undefined
  const more = groups.some((g) => (g.data?.length ?? 0) >= PER_KIND)
  let status = ''
  if (q && [...q].length < MIN_WORDS) status = t('search.tooShort')
  else if (q && !ready) status = t('search.tooLong', { count: MAX_WORDS })
  else if (count !== undefined) {
    if (count === 0) status = t('search.none', { q })
    else status = more ? t('search.many', { count, q }) : t('search.results', { count, q })
  }

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
      <QueryState loading={loading} error={error} onRetry={() => groups.forEach((g) => void g.refetch())} />
      {count === 0 ? (
        <p>
          <Link to={path(locale, 'stories')}>{t('home.allStories')} →</Link>
        </p>
      ) : null}
      <div className={styles.groups}>
        {KINDS.map(({ kind, section }, i) => {
          const rows = ready ? (groups[i].data ?? []) : []
          if (rows.length === 0) return null
          return (
            <section key={kind} aria-labelledby={`search-${kind}`}>
              <h2 id={`search-${kind}`}>
                {t(`search.kind.${kind}`)}{' '}
                <span className="label">{rows.length >= PER_KIND ? `${PER_KIND}+` : rows.length}</span>
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
