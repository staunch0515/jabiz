import { useState, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { MenuIcon, type LucideIcon } from 'lucide-react'

import { UI_NAMESPACE } from '../i18n'
import { cn } from '../lib/utils'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from './ui/sheet'

/** An entry of the bottom menu (phase 17zj): an area of the application. */
export interface BottomBarItem {
  key: string
  label: string
  icon: LucideIcon
  path: string
  badge?: number
  /** Raised in the middle of the bar (the one thing done most, such as scanning a code). */
  raised?: boolean
}

/** What an entry's link receives: pass it all on to the element rendered (a router's <Link>). */
export interface BottomBarLinkProps {
  children: ReactNode
  className: string
  'aria-current'?: 'page'
  'data-testid': string
}

export interface BottomBarProps {
  /** The entries, as many as the application wants (four with "More", five without, by convention). */
  items: BottomBarItem[]
  /** Whether an entry's path is the current page (or a page under it). */
  isActive: (path: string) => boolean
  /** The navigation landmark's name. */
  label: string
  /**
   * The whole menu, opened by a "More" entry at the end (usually the shell's {@link ShellNav}); no "More" entry when
   * absent. Following a link in it closes it.
   */
  more?: ReactNode
  /** Renders an entry's link (a router's <Link>); a plain <a href> when absent. */
  renderLink?: (item: BottomBarItem, props: BottomBarLinkProps) => ReactNode
}

function defaultLink(item: BottomBarItem, props: BottomBarLinkProps) {
  return <a href={item.path} {...props} />
}

function Badge({ count }: { count?: number }) {
  if (count === undefined || count <= 0) return null
  return (
    <span className="bg-destructive text-destructive-foreground absolute -top-1 left-1/2 ml-1.5 min-w-4 rounded-full px-1 text-center text-[0.625rem] leading-4 font-semibold">
      {count > 99 ? '99+' : count}
    </span>
  )
}

/**
 * The menu at the bottom of narrow screens (phase 17zj; Q108 sections 2 and 6), for {@link AppShell}'s `bottomBar`:
 * a few areas with their icons, the current one marked (`aria-current="page"`, and bold, not only coloured), an
 * entry may be raised in the middle, and "More" opens the whole menu in a sheet from below. The shell shows it on
 * narrow screens only, and keeps the page and the floating button clear of it.
 */
export function BottomBar({ items, isActive, label, more, renderLink = defaultLink }: BottomBarProps) {
  const { t } = useTranslation(UI_NAMESPACE)
  const [open, setOpen] = useState(false)
  const cell =
    'relative flex min-w-0 flex-1 flex-col items-center justify-center gap-0.5 px-1 text-[0.6875rem] leading-tight outline-none focus-visible:ring-2 focus-visible:ring-ring'
  // A link followed in the sheet closes it, whatever element the menu renders.
  const closeOnLink = (event: MouseEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('a[href]')) setOpen(false)
  }
  return (
    <nav
      aria-label={label}
      data-slot="bottom-bar"
      className="bg-background fixed inset-x-0 bottom-0 z-30 border-t pb-[env(safe-area-inset-bottom)]"
    >
      <ul className="flex h-(--bottom-bar-height) items-stretch">
        {items.map((item) => {
          const active = isActive(item.path)
          const Icon = item.icon
          const content = item.raised ? (
            <>
              <span className="bg-primary text-primary-foreground -mt-5 flex size-12 items-center justify-center rounded-full shadow-md">
                <Icon aria-hidden className="size-6" />
              </span>
              <span className={cn(active && 'font-semibold')}>{item.label}</span>
            </>
          ) : (
            <>
              <span className="relative">
                <Icon aria-hidden className="size-5" />
                <Badge count={item.badge} />
              </span>
              <span className={cn('max-w-full truncate', active && 'font-semibold')}>{item.label}</span>
            </>
          )
          return (
            <li key={item.key} className="flex flex-1">
              {renderLink(item, {
                children: content,
                className: cn(cell, active ? 'text-primary' : 'text-muted-foreground'),
                'aria-current': active ? 'page' : undefined,
                'data-testid': `bottom-bar-${item.key}`,
              })}
            </li>
          )
        })}
        {more !== undefined && (
          <li className="flex flex-1">
            <button
              type="button"
              className={cn(cell, 'text-muted-foreground')}
              aria-haspopup="dialog"
              aria-expanded={open}
              onClick={() => setOpen(true)}
              data-testid="bottom-bar-more"
            >
              <MenuIcon aria-hidden className="size-5" />
              <span>{t('more')}</span>
            </button>
          </li>
        )}
      </ul>
      {more !== undefined && (
        <Sheet open={open} onOpenChange={setOpen}>
          <SheetContent side="bottom" className="max-h-[85svh] overflow-y-auto" data-testid="bottom-bar-sheet">
            <SheetHeader>
              <SheetTitle>{t('more')}</SheetTitle>
              <SheetDescription className="sr-only">{label}</SheetDescription>
            </SheetHeader>
            <div className="px-2 pb-4" onClick={closeOnLink}>
              {more}
            </div>
          </SheetContent>
        </Sheet>
      )}
    </nav>
  )
}
