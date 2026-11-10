import { PageHeader, PageTabs, type PageHeaderProps } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { useLocation } from '../qb/router'
import { placeOf, shownTabs } from './areas'
import { useShellState } from './context'
import { spaLink } from './links'
import './messages'

/**
 * The head of a page that is a tab of an area (F-F3; Q108 section 3): the area's icon before the page's title, the
 * main action beside it, the description, and the area's tabs (links with addresses of their own, the current one
 * `aria-current="page"`); one tab only, no row. A page outside the areas gets the plain head.
 */
export function AreaPageHeader(props: Omit<PageHeaderProps, 'icon' | 'tabs'>) {
  const { t } = useTranslation(SPONSOR)
  const { path } = useLocation()
  const state = useShellState()
  const place = placeOf(path)
  if (!place) return <PageHeader {...props} />
  const tabs = shownTabs(place.area, state)
  return (
    <PageHeader {...props} icon={place.area.icon}
      tabs={tabs.length > 1 && (
        <PageTabs label={t(`shell.areas.${place.area.key}`)} current={place.tab.key}
          tabs={tabs.map((tab) => ({ key: tab.key, label: t(`shell.tabs.${place.area.key}.${tab.key}`), path: tab.path }))}
          renderLink={(tab, linkProps) => spaLink(tab.path, { ...linkProps })} />
      )} />
  )
}
