import { Button } from '@jabiz/ui'
import { Search } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { SEARCH_PATH } from '../frame/areas'
import { TopHead } from '../frame/Header'
import { APP } from '../messages'
import { navigate, useLocation } from '../qb/router'
import { FeedFiltersButton, FeedPage, searchOf, searchPath } from './FeedPage'
import '../frame/messages'

/**
 * The search's box (Q63; Q113 §9.2, the prototype's right column): a pill filled with the dividers' grey, the
 * magnifier first, whose search opens the results on the search's page (`/search?q=`). In the search page's head, and
 * in the right column of a wide screen.
 */
export function SearchForm({ initial = '', autoFocus = false, testId = 'feed-search' }: {
  initial?: string
  autoFocus?: boolean
  /** The field's test id; the button's is the same followed by `-submit`. */
  testId?: string
}) {
  const { t } = useTranslation(APP)
  const [text, setText] = useState(initial)
  const submit = (event: FormEvent) => {
    event.preventDefault()
    navigate(searchPath(text.trim(), SEARCH_PATH))
  }
  return (
    <form role="search" onSubmit={submit} data-testid={`${testId}-form`}
      className="bg-border text-muted-foreground focus-within:ring-primary flex h-[42px] w-full items-center gap-3 rounded-full pr-4 pl-1.5 focus-within:ring-2">
      <Button type="submit" variant="ghost" size="icon-sm" aria-label={t('feed.searchButton')}
        className="size-8 shrink-0 rounded-full hover:bg-transparent" data-testid={`${testId}-submit`}>
        <Search aria-hidden className="size-[18px]" />
      </Button>
      <input type="search" value={text} maxLength={100} placeholder={t('frame.search.title')} aria-label={t('feed.search')}
        autoFocus={autoFocus} data-testid={testId}
        className="text-foreground placeholder:text-muted-foreground min-w-0 flex-1 bg-transparent text-[15px] outline-none"
        onChange={(event) => setText(event.target.value)} />
    </form>
  )
}

/**
 * The search's page (Q113 §9.2, the bottom menu's second entry): a first-level head whose title is the box, the
 * filters at its right, and the feed searched under it (the home's cards, shorts and filters, Q63); a hint before the
 * first search. The home's own `/?q=` stays as it was, for the links that have it.
 */
export function SearchPage({ name, country }: { name: string; country: string | null }) {
  const { t } = useTranslation(APP)
  const q = searchOf(useLocation().search)
  return (
    <section className="flex flex-col" aria-labelledby="search-title" data-testid="search">
      {/* The key: a new search (from the right column, the history) starts the box again with its words. */}
      <TopHead title={t('frame.search.title')} id="search-title" bleed actions={q ? <FeedFiltersButton /> : undefined}
        field={<SearchForm key={q} initial={q} autoFocus={!q} />} />
      {q ? (
        <div className="pt-4">
          <FeedPage name={name} country={country} base={SEARCH_PATH} />
        </div>
      ) : (
        <p className="text-muted-foreground py-6 text-[15px] leading-5" data-testid="search-hint">{t('frame.search.hint')}</p>
      )}
    </section>
  )
}
