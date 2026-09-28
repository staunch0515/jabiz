import { lazy, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'
import { loadMarkdown } from './components/markdownLoader'
import { LanguageRedirect, Localized } from './pages/Localized'
import { Home } from './pages/Home'
import { NotFound } from './pages/NotFound'
import { RouteError } from './pages/RouteError'

/**
 * Every page but the home page is loaded when it is first opened (design section 9.4: the first screen's JavaScript
 * stays small). The home page is in the first bundle: it is where most visits start. A page with editors' texts
 * (`markdown`) loads the Markdown renderer with itself, so that its texts are there when it shows.
 */
function page<K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K, markdown = false) {
  const Page = lazy(async (): Promise<{ default: ComponentType }> => {
    const [module] = await Promise.all([load(), markdown ? loadMarkdown() : null])
    return { default: module[name] }
  })
  return <Page />
}

const pages: RouteObject[] = [
  { index: true, element: <Home /> },
  { path: 'people', element: page(() => import('./pages/People'), 'People') },
  { path: 'people/:slug', element: page(() => import('./pages/Person'), 'Person', true) },
  { path: 'themes', element: page(() => import('./pages/Themes'), 'Themes') },
  { path: 'themes/:slug', element: page(() => import('./pages/Theme'), 'Theme', true) },
  { path: 'stories', element: page(() => import('./pages/Stories'), 'Stories') },
  { path: 'stories/:slug', element: page(() => import('./pages/Story'), 'Story', true) },
  { path: 'method', element: page(() => import('./pages/Method'), 'Method', true) },
  { path: 'resources', element: page(() => import('./pages/Resources'), 'Resources') },
  { path: 'resources/:slug', element: page(() => import('./pages/Resource'), 'Resource', true) },
  { path: 'about', element: page(() => import('./pages/About'), 'About', true) },
  { path: 'map', element: page(() => import('./pages/Map'), 'Map') },
  { path: 'search', element: page(() => import('./pages/Search'), 'Search') },
  { path: '*', element: <NotFound /> },
]

export const routes: RouteObject[] = [
  { path: '/', element: <LanguageRedirect /> },
  {
    path: '/:lang',
    element: <Localized />,
    // A page that fails shows the error inside the site's header and footer.
    children: pages.map((route) => ({ ...route, errorElement: <RouteError /> })),
  },
]
