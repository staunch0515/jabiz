import { useState, type ReactNode } from 'react'
import { Collapsible } from 'radix-ui'
import { ChevronRightIcon, type LucideIcon } from 'lucide-react'

import { cn, UI_SCOPE } from '../lib/utils'
import { Separator } from './ui/separator'
import {
  Sidebar,
  SidebarContent,
  SidebarFooter,
  SidebarGroup,
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
  SidebarTrigger,
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
   * Classes of the page's container. While Ant Design pages remain (phase 15) the admin keeps their area in the
   * light theme (`light`), which the scoped preflight does not reach.
   */
  contentClassName?: string
}

/**
 * The frame of a signed-in application (decision D34 item 4): a collapsible sidebar (a sheet on narrow screens,
 * Ctrl/⌘+B toggles it), a header bar and the page. The sidebar and header carry `.jabiz-ui`; the page area does
 * not, so pages built with Ant Design keep their own styles until they move.
 */
export function AppShell({ brand, navigation, sidebarFooter, header, children, contentClassName }: AppShellProps) {
  return (
    <SidebarProvider>
      <Sidebar collapsible="icon">
        <SidebarHeader>{brand}</SidebarHeader>
        <SidebarContent>{navigation}</SidebarContent>
        {sidebarFooter && <SidebarFooter>{sidebarFooter}</SidebarFooter>}
        <SidebarRail />
      </Sidebar>
      <SidebarInset>
        <header
          data-slot="app-shell-header"
          className={cn(
            UI_SCOPE,
            'bg-background sticky top-0 z-20 flex h-12 shrink-0 items-center gap-2 border-b px-3',
          )}
        >
          <SidebarTrigger className="-ml-1" />
          <Separator orientation="vertical" className="mr-1 data-[orientation=vertical]:h-4" />
          {header}
        </header>
        <div data-slot="app-shell-content" className={cn('flex-1 p-4', contentClassName)}>
          {children}
        </div>
      </SidebarInset>
    </SidebarProvider>
  )
}

export interface ShellNavProps {
  items: ShellMenuItem[]
  /** Whether an entry's path is the current page (or a page under it). */
  isActive: (path: string) => boolean
  /**
   * Renders an entry's link (a router's <Link>); it receives the props the menu puts on it and must pass them on
   * to the element it renders. A plain <a href> when absent.
   */
  renderLink?: (item: ShellMenuItem & { path: string }, props: { children: ReactNode }) => ReactNode
  /** The navigation landmark's name. */
  label: string
}

function contains(item: ShellMenuItem, isActive: (path: string) => boolean): boolean {
  return (item.path !== undefined && isActive(item.path)) || (item.children ?? []).some((c) => contains(c, isActive))
}

function defaultLink(item: ShellMenuItem & { path: string }, props: { children: ReactNode }) {
  return <a href={item.path} {...props} />
}

/** The sidebar's menu tree: links, groups that open and close (open when they hold the current page), badges. */
export function ShellNav({ items, isActive, renderLink = defaultLink, label }: ShellNavProps) {
  return (
    <nav aria-label={label}>
      <SidebarGroup>
        <SidebarMenu>
          {items.map((item) => (
            <ShellNavItem key={item.key} item={item} isActive={isActive} renderLink={renderLink} />
          ))}
        </SidebarMenu>
      </SidebarGroup>
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
            <SidebarMenuButton tooltip={item.label} isActive={!open && contains(item, isActive)}>
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
    ? renderLink({ ...item, path: item.path }, { children: content })
    : <span>{content}</span>
  return (
    <Item data-testid={`menu-${item.key}`}>
      {nested ? (
        <SidebarMenuSubButton asChild isActive={active} aria-current={active ? 'page' : undefined}>
          {link}
        </SidebarMenuSubButton>
      ) : (
        <SidebarMenuButton asChild isActive={active} tooltip={item.label} aria-current={active ? 'page' : undefined}>
          {link}
        </SidebarMenuButton>
      )}
      {!nested && item.badge !== undefined && item.badge > 0 && (
        <SidebarMenuBadge>{item.badge > 99 ? '99+' : item.badge}</SidebarMenuBadge>
      )}
    </Item>
  )
}
