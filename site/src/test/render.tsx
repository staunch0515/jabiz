import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import type { Locale } from '../api/public-queries'
import { LocaleContext } from '../i18n/locale'

/** Renders an element in a language, with a query client and a router at `path` (for components with links). */
export function renderIn(element: ReactElement, { locale = 'en', path = '/' }: { locale?: Locale; path?: string } = {}) {
  return renderRoutes([{ path: '*', element }], { locale, path })
}

export function renderRoutes(routes: RouteObject[], { locale = 'en', path = '/' }: { locale?: Locale; path?: string } = {}) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const result = render(
    <QueryClientProvider client={client}>
      <LocaleContext.Provider value={locale}>
        <RouterProvider router={router} />
      </LocaleContext.Provider>
    </QueryClientProvider>,
  )
  return { ...result, router }
}
