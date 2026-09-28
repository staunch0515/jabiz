import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { preloadMarkdown } from './components/markdownLoader'
import { routes } from './routes'
import './i18n'
import './styles/global.css'

// Public templates are cached by the server for a minute (design section 7.2); the site does not ask again sooner.
const queryClient = new QueryClient({
  defaultOptions: { queries: { staleTime: 60_000, retry: 1, refetchOnWindowFocus: false } },
})

const router = createBrowserRouter(routes, { basename: import.meta.env.BASE_URL.replace(/\/$/, '') || '/' })

createRoot(document.getElementById('site')!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>
  </StrictMode>,
)

// The renderer of the editors' texts, which most pages need: fetched a little after the page has loaded, so that it
// does not take bandwidth from what the first screen is waiting for (its data, its fonts and images).
window.addEventListener('load', () => setTimeout(preloadMarkdown, 1500), { once: true })
