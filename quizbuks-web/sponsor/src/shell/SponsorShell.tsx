import { AppShell, BottomBar, GlobalCreate, PageContent, ShellNav, type CreateItem, type ShellMenuItem } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { FileQuestion, FileUp, Library, Megaphone, Send } from 'lucide-react'
import { useRef, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { QUIZZES_PATH } from '../content/paths'
import { SPONSOR } from '../messages'
import { ACCOUNT_PATH } from '../pages/AccountPage'
import { ImportSettingsButton } from '../publishing/PublicationTransfer'
import { NEW_PUBLICATION, publicationPath } from '../publishing/paths'
import { Wordmark } from '../qb/Illustrations'
import { AREAS, areaShown, bottomBarAreas, placeOf, rootOf, type AreaKey, type ShellState } from './areas'
import { AvatarMenu } from './AvatarMenu'
import { ShellContext } from './context'
import { DEFAULT_BOTTOM_BAR, LAYOUT_QUERY, readLayout } from './layout'
import { spaLink } from './links'
import { ASSETS_PATH, NEW_PARAM, NOTICES_PATH } from './paths'
import './messages'

/**
 * The frame of a signed-in Sponsor (F-F3; Q108 sections 2 and 3, the platform's 17zj): the menu of the home and the
 * eight areas (a strip of icons when collapsed, kept on this device by the platform), "+ New" at its top, the avatar
 * menu at the top right, and on a phone the bottom menu of the areas the parameter `qb.sponsor.bottom-bar` names, with
 * "More" opening the whole menu. The focus moves to the page's title when the route changes.
 */
export function SponsorShell({ path, state, badges, displayName, helpPath, onSignOut, tools, children }: {
  path: string
  state: ShellState
  /** Counts after the areas' names (the conversations waiting, Q82). */
  badges: Partial<Record<AreaKey, number>>
  displayName: string | null | undefined
  helpPath: string
  onSignOut: () => void
  /** What the header shows before the avatar menu (the AI help of F-C10c, over any page). */
  tools?: ReactNode
  children: ReactNode
}) {
  const { t } = useTranslation(SPONSOR)
  const importSettings = useRef<{ open: () => void }>(null)
  const layout = useQuery({ queryKey: LAYOUT_QUERY, queryFn: readLayout, staleTime: Infinity, retry: false })
  const current = placeOf(path)?.area.key
  const shown = AREAS.filter((area) => areaShown(area, state))
  // The menu's entries are the areas: an entry is current for any page of its area.
  const isActive = (entry: string) => placeOf(entry)?.area.key === current
  const items: ShellMenuItem[] = shown.map((area) => ({
    key: area.key, label: t(`shell.areas.${area.key}`), path: rootOf(area, state), icon: area.icon,
    badge: badges[area.key],
  }))
  const nav = (
    <ShellNav label={t('shell.menu')} items={items} isActive={isActive}
      renderLink={(item, props) => spaLink(item.path, { children: props.children }, props.onClick)} />
  )
  const bottom = bottomBarAreas(layout.data?.bottomBar ?? DEFAULT_BOTTOM_BAR)
    .map((key) => shown.find((area) => area.key === key))
    .filter((area) => area !== undefined)
  const create: CreateItem[] = [
    { key: 'quiz', label: t('shell.create.quiz'), icon: FileQuestion, path: `${QUIZZES_PATH}?${NEW_PARAM}=1` },
    { key: 'publication', label: t('shell.create.publication'), icon: Megaphone,
      path: publicationPath(NEW_PUBLICATION) },
    { key: 'notice', label: t('shell.create.notice'), icon: Send, path: `${NOTICES_PATH}?${NEW_PARAM}=1` },
    { key: 'import-settings', label: t('shell.create.importSettings'), icon: FileUp,
      onSelect: () => importSettings.current?.open() },
    { key: 'asset', label: t('shell.create.asset'), icon: Library, path: `${ASSETS_PATH}?${NEW_PARAM}=1` },
  ]
  return (
    <ShellContext.Provider value={state}>
      <AppShell
        brand={<Wordmark name={t('brand')} compact className="px-2 py-1" />}
        collapsible="icon"
        routeKey={path}
        create={<GlobalCreate label={t('shell.create.label')} items={create}
          renderLink={(item, props) => spaLink(item.path, { ...props })} />}
        navigation={nav}
        bottomBar={bottom.length > 0 ? (
          <BottomBar label={t('shell.bottomMenu')} isActive={isActive} more={nav}
            items={bottom.map((area) => ({ key: area.key, label: t(`shell.bottom.${area.key}`), icon: area.icon,
              path: rootOf(area, state), badge: badges[area.key] }))}
            renderLink={(item, props) => spaLink(item.path, { ...props })} />
        ) : undefined}
        header={
          <div className="ml-auto flex items-center gap-2">
            {tools}
            <AvatarMenu name={displayName} accountPath={ACCOUNT_PATH} helpPath={helpPath} onSignOut={onSignOut} />
          </div>
        }
      >
        <PageContent>{children}</PageContent>
        <ImportSettingsButton opener={importSettings} />
      </AppShell>
    </ShellContext.Provider>
  )
}
