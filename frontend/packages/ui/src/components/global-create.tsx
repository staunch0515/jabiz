import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { PlusIcon, type LucideIcon } from 'lucide-react'

import { UI_NAMESPACE } from '../i18n'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from './ui/dropdown-menu'
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar } from './ui/sidebar'

/** Something that can be created from anywhere (phase 17zj): a new record, a message, an import. */
export interface CreateItem {
  key: string
  label: string
  icon?: LucideIcon
  /** Where creating it starts (a form's route); or {@link onSelect}. */
  path?: string
  onSelect?: () => void
}

/** What a create item's link receives: pass it all on to the element rendered (a router's <Link>). */
export interface CreateLinkProps {
  children: ReactNode
  'data-testid': string
}

export interface GlobalCreateProps {
  /** What can be created, in order; the application gives them (only those the user may create). */
  items: CreateItem[]
  /** The button's text; "New" in the user's language when absent. */
  label?: string
  /** Renders an item's link (a router's <Link>); a plain <a href> when absent. */
  renderLink?: (item: CreateItem & { path: string }, props: CreateLinkProps) => ReactNode
}

function defaultLink(item: CreateItem & { path: string }, props: CreateLinkProps) {
  return <a href={item.path} {...props} />
}

/**
 * The "+ New" button at the top of the sidebar (phase 17zj; Q108 section 3), for {@link AppShell}'s `create`: a menu
 * of everything that can be created, whatever page is open. Collapsed to icons, only the plus shows (named by its
 * text). Nothing to create: nothing shows.
 */
export function GlobalCreate({ items, label, renderLink = defaultLink }: GlobalCreateProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const { isMobile, setOpenMobile } = useSidebar()
  if (items.length === 0) return null
  const text = label ?? t('create.label')
  const followed = () => {
    if (isMobile) setOpenMobile(false)
  }
  return (
    <SidebarMenu data-slot="global-create">
      <SidebarMenuItem>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <SidebarMenuButton
              tooltip={text}
              className="bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground data-[state=open]:bg-primary/90 data-[state=open]:text-primary-foreground font-medium"
              data-testid="global-create"
            >
              <PlusIcon aria-hidden />
              <span>{text}</span>
            </SidebarMenuButton>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="start" side={isMobile ? 'bottom' : 'right'} className="min-w-48">
            {items.map((item) => {
              const Icon = item.icon
              const content = (
                <>
                  {Icon && <Icon aria-hidden />}
                  {item.label}
                </>
              )
              if (item.path !== undefined) {
                return (
                  <DropdownMenuItem key={item.key} asChild onSelect={followed}>
                    {renderLink({ ...item, path: item.path }, {
                      children: content,
                      'data-testid': `global-create-${item.key}`,
                    })}
                  </DropdownMenuItem>
                )
              }
              return (
                <DropdownMenuItem
                  key={item.key}
                  data-testid={`global-create-${item.key}`}
                  onSelect={() => {
                    followed()
                    item.onSelect?.()
                  }}
                >
                  {content}
                </DropdownMenuItem>
              )
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      </SidebarMenuItem>
    </SidebarMenu>
  )
}
