import type { RouteObject } from 'react-router'
import { LanguageRedirect, Localized } from './pages/Localized'
import { About } from './pages/About'
import { Home } from './pages/Home'
import { Method } from './pages/Method'
import { NotFound } from './pages/NotFound'
import { People } from './pages/People'
import { Person } from './pages/Person'
import { Resource } from './pages/Resource'
import { Resources } from './pages/Resources'
import { Stories } from './pages/Stories'
import { Story } from './pages/Story'
import { Theme } from './pages/Theme'
import { Themes } from './pages/Themes'

export const routes: RouteObject[] = [
  { path: '/', element: <LanguageRedirect /> },
  {
    path: '/:lang',
    element: <Localized />,
    children: [
      { index: true, element: <Home /> },
      { path: 'people', element: <People /> },
      { path: 'people/:slug', element: <Person /> },
      { path: 'themes', element: <Themes /> },
      { path: 'themes/:slug', element: <Theme /> },
      { path: 'stories', element: <Stories /> },
      { path: 'stories/:slug', element: <Story /> },
      { path: 'method', element: <Method /> },
      { path: 'resources', element: <Resources /> },
      { path: 'resources/:slug', element: <Resource /> },
      { path: 'about', element: <About /> },
      { path: '*', element: <NotFound /> },
    ],
  },
]
