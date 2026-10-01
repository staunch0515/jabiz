import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { App } from 'antd'
import i18next from 'i18next'
import type { ReactNode } from 'react'
import { initReactI18next } from 'react-i18next'
import { MemoryRouter, Route, Routes } from 'react-router'
import { EXTENSION_NAMESPACE } from '@jabiz/admin'
import { messages } from '../messages'

/** i18next with the finance texts, as the admin frontend sets it up. */
export async function setUpTexts() {
  await i18next.use(initReactI18next).init({
    lng: 'en',
    resources: { en: { [EXTENSION_NAMESPACE]: messages.en } },
    interpolation: { escapeValue: false },
  })
}

/** Renders pages under the given routes, starting at a path. */
export function renderAt(path: string, routes: { path: string; element: ReactNode }[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            {routes.map((route) => (
              <Route key={route.path} path={route.path} element={route.element} />
            ))}
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}
