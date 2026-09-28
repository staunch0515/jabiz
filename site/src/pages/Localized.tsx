import { Suspense } from 'react'
import { Navigate, Outlet, useParams } from 'react-router'
import { Layout } from '../components/Layout'
import { QueryState } from '../components/QueryState'
import { LocaleContext } from '../i18n/locale'
import { isLocale, preferredLocale } from '../lib/localized'
import { NotFound } from './NotFound'

/** `/` goes to the browser's language among ours, English otherwise (design section 9.1). */
export function LanguageRedirect() {
  return <Navigate to={`/${preferredLocale(navigator.languages ?? [navigator.language])}/`} replace />
}

/** Every page is under its language; an address whose first segment is not one of ours is not a page. */
export function Localized() {
  const { lang } = useParams()
  const known = isLocale(lang)
  return (
    <LocaleContext.Provider value={known ? lang : 'en'}>
      <Layout>
        {known ? (
          // A page opened for the first time is loaded first (routes.tsx).
          <Suspense
            fallback={
              <div className="wrap page-head">
                <QueryState loading error={null} />
              </div>
            }
          >
            <Outlet />
          </Suspense>
        ) : (
          <NotFound />
        )}
      </Layout>
    </LocaleContext.Provider>
  )
}
