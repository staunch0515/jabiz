import { lazy, type ComponentType } from 'react'
import type { RouteObject } from 'react-router'
import { LanguageRedirect, Localized } from './pages/Localized'
import { Home } from './pages/Home'
import { NotFound } from './pages/NotFound'

/**
 * Every page but the home page is loaded when it is first opened (design section 9.4: the first screen's JavaScript
 * stays small). The home page is in the first bundle: it is where most visits start.
 */
function page<K extends string>(load: () => Promise<Record<K, ComponentType>>, name: K) {
  const Page = lazy(async (): Promise<{ default: ComponentType }> => ({ default: (await load())[name] }))
  return <Page />
}

export const routes: RouteObject[] = [
  { path: '/', element: <LanguageRedirect /> },
  {
    path: '/:lang',
    element: <Localized />,
    children: [
      { index: true, element: <Home /> },
      { path: 'people', element: page(() => import('./pages/People'), 'People') },
      { path: 'people/:slug', element: page(() => import('./pages/Person'), 'Person') },
      { path: 'themes', element: page(() => import('./pages/Themes'), 'Themes') },
      { path: 'themes/:slug', element: page(() => import('./pages/Theme'), 'Theme') },
      { path: 'stories', element: page(() => import('./pages/Stories'), 'Stories') },
      { path: 'stories/:slug', element: page(() => import('./pages/Story'), 'Story') },
      { path: 'method', element: page(() => import('./pages/Method'), 'Method') },
      { path: 'resources', element: page(() => import('./pages/Resources'), 'Resources') },
      { path: 'resources/:slug', element: page(() => import('./pages/Resource'), 'Resource') },
      { path: 'about', element: page(() => import('./pages/About'), 'About') },
      { path: 'map', element: page(() => import('./pages/Map'), 'Map') },
      { path: 'search', element: page(() => import('./pages/Search'), 'Search') },
      { path: '*', element: <NotFound /> },
    ],
  },
]
