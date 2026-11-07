import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react'
import { Collapsible } from 'radix-ui'
import { ChevronRightIcon, type LucideIcon } from 'lucide-react'

import { useIsMobile } from '../hooks/use-mobile'
import { cn } from '../lib/utils'
import { EnvironmentBanner } from './environment-banner'
import { Separator } from './ui/separator'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
  SidebarGroupLabel,
  SidebarHeader,
  SidebarInset,
  SidebarMenu,
  SidebarMenuBadge,
  SidebarMenuButton,
  SidebarMenuItem,
  SidebarMenuSub,
  SidebarMenuSubButton,
  SidebarMenuSubItem,
  SidebarProvider,
  SidebarRail,
  SidebarSeparator,
  SidebarTrigger,
  useSidebar,
} from './ui/sidebar'

/**
 * An entry of the shell's menu: the platform's own menu type (the admin's server menus, an extension's entries and
 * the fixed pages all become these), independent of any router.
 */
export interface ShellMenuItem {
  key: string
  label: string
  /** Where the entry leads; an entry with children and no path only groups them. */
  path?: string
  icon?: LucideIcon
  /** A count shown at the end of the entry (open tasks). */
  badge?: number
  children?: ShellMenuItem[]
}

/**
 * A section of the menu (phase 16zi): entries that belong together by what they are for, with an optional heading;
 * a rule separates one section from the next.
 */
export interface ShellMenuGroup {
  key: string
  /** The section's heading; a section without one is only set apart by the rules. */
  label?: string
  items: ShellMenuItem[]
}

export interface AppShellProps {
  /** The application's name or logo, at the top of the sidebar. */
  brand: ReactNode
  /** The sidebar's navigation, usually a {@link ShellNav}. */
  navigation: ReactNode
  /** At the bottom of the sidebar. */
  sidebarFooter?: ReactNode
  /** The header bar after the sidebar toggle: a title or breadcrumb, then actions (use `ml-auto` to push right). */
  header?: ReactNode
  /** The page. */
  children: ReactNode
  /**
   * Classes of the page's container (its background, for one).
   */
  contentClassName?: string
  /**
   * How the sidebar collapses on wide screens (phase 17zj): `offcanvas` (the default) leaves the screen; `icon`
   * shrinks to a strip of the entries' icons, their names as tooltips (entries need icons). Either way the state is
   * the user's, kept on this device.
   */
  collapsible?: 'offcanvas' | 'icon'
  /** Under the brand, at the top of the sidebar: usually a {@link GlobalCreate} ("+ New"). */
  create?: ReactNode
  /**
   * The menu at the bottom of narrow screens, usually a {@link BottomBar}; shown there only, with the page and a
   * floating button kept clear of it.
   */
  bottomBar?: ReactNode
  /**
   * Changes with the route (the location's path): the focus then moves to the new page's title (the `<h1>` of its
   * {@link PageHeader}), so that a screen reader announces the page. Nothing moves when it stays the same.
   */
  routeKey?: string
}

/**
 * The frame of a signed-in application (decision D34 item 4): a collapsible sidebar (a sheet on narrow screens,
 * Ctrl/⌘+B toggles it), a header bar and the page; optionally (phase 17zj) a create button, a sidebar that collapses
 * to icons, and a bottom menu on narrow screens.
 */
export function AppShell({
  brand,
  navigation,
  sidebarFooter,
  header,
  children,
  contentClassName,
  collapsible = 'offcanvas',
  create,
  bottomBar,
  routeKey,
}: AppShellProps) {
  const mobile = useIsMobile()
  const content = useRef<HTMLDivElement>(null)
  const shownRoute = useRef(routeKey)
  useEffect(() => {
    if (routeKey === shownRoute.current) return
    shownRoute.current = routeKey
    content.current?.querySelector<HTMLElement>('[data-slot="page-title"]')?.focus()
  }, [routeKey])
  const withBottomBar = bottomBar !== undefined && mobile
  const style = withBottomBar ? ({ '--bottom-bar-offset': 'var(--bottom-bar-height)' } as CSSProperties) : undefined
  return (
    <SidebarProvider style={style}>
      {/*
        By default, collapsed, the sidebar leaves the screen (and is inert) rather than shrinking to icons: menu
        entries from the server have no icon. An application whose entries all have icons may ask for the strip.
      */}
      <Sidebar collapsible={collapsible}>
        <SidebarHeader>
          {brand}
          {create}
        </SidebarHeader>
        <SidebarContent>{navigation}</SidebarContent>
        {sidebarFooter && <SidebarFooter>{sidebarFooter}</SidebarFooter>}
        <SidebarRail />
      </Sidebar>
      <SidebarInset>
        {/* The environment's banner (a test system, phase 16zd) and the header bar stay on screen together. */}
        <div className="sticky top-0 z-20 shrink-0">
          <EnvironmentBanner />
          <header
            data-slot="app-shell-header"
            className={cn('bg-background text-foreground flex h-12 shrink-0 items-center gap-2 border-b px-3')}
          >
            <SidebarTrigger className="-ml-1" />
            <Separator orientation="vertical" className="mr-1 data-[orientation=vertical]:h-4" />
            {header}
          </header>
        </div>
        <div
          ref={content}
          data-slot="app-shell-content"
          className={cn(
            'flex-1 p-4',
            withBottomBar && 'pb-[calc(var(--bottom-bar-height)+env(safe-area-inset-bottom)+1rem)]',
            contentClassName,
          )}
        >
          {children}
        </div>
        {withBottomBar && bottomBar}
      </SidebarInset>
    </SidebarProvider>
  )
}

export interface ShellNavProps {
  /** The entries, in one section. */
  items?: ShellMenuItem[]
  /** The entries in sections, separated by rules (phase 16zi); used instead of `items`. Empty sections are left out. */
  groups?: ShellMenuGroup[]
  /** Whether an entry's path is the current page (or a page under it). */
  isActive: (path: string) => boolean
  /**
   * Renders an entry's link (a router's <Link>); it receives the props the menu puts on it and must pass them on
   * to the element it renders. A plain <a href> when absent.
   */
  renderLink?: (item: ShellMenuItem & { path: string }, props: LinkProps) => ReactNode
  /** The navigation landmark's name. */
  label: string
}

function contains(item: ShellMenuItem, isActive: (path: string) => boolean): boolean {
  return (item.path !== undefined && isActive(item.path)) || (item.children ?? []).some((c) => contains(c, isActive))
}

/** What the menu puts on an entry's link: its content, and closing the sheet on narrow screens once followed. */
export interface LinkProps {
  children: ReactNode
  onClick: () => void
}

function defaultLink(item: ShellMenuItem & { path: string }, props: LinkProps) {
  return <a href={item.path} {...props} />
}

/**
 * The sidebar's menu tree: links, groups that open and close (open when they hold the current page), badges; in
 * sections with rules between them when given `groups`. A section with a heading is a named group for assistive
 * technology.
 */
export function ShellNav({ items, groups, isActive, renderLink = defaultLink, label }: ShellNavProps) {
  const sections = (groups ?? [{ key: 'menu', items: items ?? [] }]).filter((group) => group.items.length > 0)
  return (
    <nav aria-label={label}>
      {sections.map((group, index) => {
        const headingId = group.label ? `shell-nav-${group.key}` : undefined
        return (
          <div key={group.key} data-testid={groups ? `menu-group-${group.key}` : undefined}>
            {index > 0 && <SidebarSeparator data-testid="menu-separator" />}
            <SidebarGroup role={group.label ? 'group' : undefined} aria-labelledby={headingId}>
              {group.label && <SidebarGroupLabel id={headingId}>{group.label}</SidebarGroupLabel>}
              <SidebarMenu>
                {group.items.map((item) => (
                  <ShellNavItem key={item.key} item={item} isActive={isActive} renderLink={renderLink} />
                ))}
              </SidebarMenu>
            </SidebarGroup>
          </div>
        )
      })}
    </nav>
  )
}

interface ItemProps {
  item: ShellMenuItem
  isActive: (path: string) => boolean
  renderLink: NonNullable<ShellNavProps['renderLink']>
  nested?: boolean
}

function ShellNavItem({ item, isActive, renderLink, nested = false }: ItemProps) {
  const [open, setOpen] = useState(() => contains(item, isActive))
  const { isMobile, setOpenMobile, state, setOpen: setSidebarOpen } = useSidebar()
  // On narrow screens the menu is a sheet over the page: following a link closes it.
  const followed = () => {
    if (isMobile) setOpenMobile(false)
  }
  const Icon = item.icon
  const content = (
    <>
      {Icon && <Icon aria-hidden />}
      <span>{item.label}</span>
    </>
  )
  const Item = nested ? SidebarMenuSubItem : SidebarMenuItem

  if (item.children && item.children.length > 0) {
    return (
      <Collapsible.Root open={open} onOpenChange={setOpen} asChild>
        <Item data-testid={`menu-${item.key}`}>
          <Collapsible.Trigger asChild>
            <SidebarMenuButton
              isActive={!open && contains(item, isActive)}
              tooltip={nested ? undefined : item.label}
              // In the icon strip the entries of a group cannot show: opening it widens the sidebar first.
              onClick={(event) => {
                if (state === 'collapsed' && !isMobile) {
                  // Not Radix's toggle: the group opens, whatever it was.
                  event.preventDefault()
                  setSidebarOpen(true)
                  setOpen(true)
                }
              }}
            >
              {content}
              <ChevronRightIcon
                aria-hidden
                className={cn('ml-auto transition-transform duration-200', open && 'rotate-90')}
              />
            </SidebarMenuButton>
          </Collapsible.Trigger>
          <Collapsible.Content>
            <SidebarMenuSub>
              {item.children.map((child) => (
                <ShellNavItem key={child.key} item={child} isActive={isActive} renderLink={renderLink} nested />
              ))}
            </SidebarMenuSub>
          </Collapsible.Content>
        </Item>
      </Collapsible.Root>
    )
  }

  const active = item.path !== undefined && isActive(item.path)
  const link = item.path !== undefined
    ? renderLink({ ...item, path: item.path }, { children: content, onClick: followed })
    : <span>{content}</span>
  return (
    <Item data-testid={`menu-${item.key}`}>
      {nested ? (
        <SidebarMenuSubButton asChild isActive={active} aria-current={active ? 'page' : undefined}>
          {link}
        </SidebarMenuSubButton>
      ) : (
        <SidebarMenuButton asChild isActive={active} aria-current={active ? 'page' : undefined} tooltip={item.label}>
          {link}
        </SidebarMenuButton>
      )}
      {!nested && item.badge !== undefined && item.badge > 0 && (
        <SidebarMenuBadge>{item.badge > 99 ? '99+' : item.badge}</SidebarMenuBadge>
      )}
    </Item>
  )
}
