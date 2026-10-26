import { useState, type ReactNode } from 'react'
import { Collapsible } from 'radix-ui'
import { ChevronRightIcon, type LucideIcon } from 'lucide-react'

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
}

/**
 * The frame of a signed-in application (decision D34 item 4): a collapsible sidebar (a sheet on narrow screens,
 * Ctrl/⌘+B toggles it), a header bar and the page.
 */
export function AppShell({ brand, navigation, sidebarFooter, header, children, contentClassName }: AppShellProps) {
  return (
    <SidebarProvider>
      {/*
        Collapsed, the sidebar leaves the screen (and is inert) rather than shrinking to icons: menu entries from the
        server have no icon, and groups could not open in an icon strip.
      */}
      <Sidebar collapsible="offcanvas">
        <SidebarHeader>{brand}</SidebarHeader>
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
        <div data-slot="app-shell-content" className={cn('flex-1 p-4', contentClassName)}>
          {children}
        </div>
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
  const { isMobile, setOpenMobile } = useSidebar()
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
            <SidebarMenuButton isActive={!open && contains(item, isActive)}>
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
        <SidebarMenuButton asChild isActive={active} aria-current={active ? 'page' : undefined}>
          {link}
        </SidebarMenuButton>
      )}
      {!nested && item.badge !== undefined && item.badge > 0 && (
        <SidebarMenuBadge>{item.badge > 99 ? '99+' : item.badge}</SidebarMenuBadge>
      )}
    </Item>
  )
}
