import { Link } from 'react-router'
import { usePublicQuery } from '../api/hooks'
import cards from '../components/Cards.module.css'
import { PageMeta } from '../components/PageMeta'
import { PersonCard } from '../components/PersonCard'
import { QueryState } from '../components/QueryState'
import { useLocale, useT } from '../i18n/locale'
import { path } from '../lib/paths'

/** PEOPLE (brief section 6): a card for every participant, in the editors' order. */
export function People() {
  const t = useT()
  const locale = useLocale()
  const people = usePublicQuery('culture.public.people', {}, { limit: 100 })
  return (
    <div className="wrap">
      <PageMeta title={t('people.title')} description={t('people.intro')} />
      <div className="page-head">
        <h1>{t('people.title')}</h1>
        <p className="intro">{t('people.intro')}</p>
        {/* The map is a way in from here, not from the main navigation (design section 9.1). */}
        <p>
          <Link className="btn" to={path(locale, 'map')}>
            {t('map.seeMap')} <span aria-hidden="true">→</span>
          </Link>
        </p>
      </div>
      <QueryState loading={people.isLoading} error={people.error} onRetry={() => void people.refetch()} />
      <ul className={cards.grid4}>
        {(people.data ?? []).map((person, index) => (
          <li key={person.slug}>
            <PersonCard person={person} headingLevel={2} eager={index < 4} />
          </li>
        ))}
      </ul>
    </div>
  )
}
