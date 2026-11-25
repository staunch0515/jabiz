import { useAuth } from '@jabiz/client'
import { Button, cn, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { LogOut, Store } from 'lucide-react'
import { createContext, useContext, useMemo, useState, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { MY_SPONSOR_PATH, useMySponsor } from '../mysponsor/api'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { readMyProfile } from '../qb/api'
import { QB } from '../qb/messages'
import { DRAWER_ROWS, PRIZES_PATH, type MeRow } from './areas'
import { FrameLink } from './FrameLink'
import { useOpenPrizes } from './prizes'
import { useSignOut } from './signOut'
import { xspecVariables } from './xspec'
import './messages'

/*
 * The drawer of the taker app (Q113 §9.1 "侧边抽屉", §9.2): from the left, 280 px, opened by the avatar of a head or the
 * account at the foot of the left column. Who is signed in and a line of numbers, then the rows of `DRAWER_ROWS`
 * (text 20 px bold, with their icons), "My Sponsor account" for a Sponsor, and signing out. A row followed closes it.
 */

const DrawerState = createContext<{ open: boolean; setOpen: (open: boolean) => void } | null>(null)

/** The drawer's state, for the frame's avatars; null outside the frame (a head drawn alone in a test). */
export function useDrawer() {
  return useContext(DrawerState)
}

/** The drawer and its state, around the frame's pages. */
export function DrawerProvider({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false)
  const state = useMemo(() => ({ open, setOpen }), [open])
  return (
    <DrawerState.Provider value={state}>
      {children}
      <Drawer open={open} onOpenChange={setOpen} />
    </DrawerState.Provider>
  )
}

/** The taker's initial in a circle of `className`'s size. */
export function Initial({ className }: { className?: string }) {
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const initial = (profile.data?.displayName || email || '?').trim().charAt(0).toUpperCase()
  return (
    <span aria-hidden className={cn('bg-primary text-primary-foreground flex shrink-0 items-center justify-center rounded-full',
      'font-semibold', className)}>
      {initial}
    </span>
  )
}

function DrawerRow({ row }: { row: MeRow }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const Icon = row.icon
  return (
    <li>
      <FrameLink to={row.path} data-testid={`drawer-${row.key}`}
        className={cn('hover:bg-accent focus-visible:ring-ring flex items-center gap-(--x-side) rounded-full px-(--x-side) py-3',
          'text-(length:--x-drawer-text) leading-6 font-bold outline-none focus-visible:ring-2')}>
        <Icon aria-hidden className="size-6 shrink-0" />
        <span className="truncate">{row.ns === 'qb' ? tQb(row.title) : t(row.title)}</span>
      </FrameLink>
    </li>
  )
}

function Drawer({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const sponsor = useMySponsor().data
  const prizes = useOpenPrizes(open)
  const signOut = useSignOut()
  const name = profile.data?.displayName || email || ''
  // A link followed in the drawer closes it, whichever row it was.
  const closeOnLink = (event: MouseEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('a[href]')) onOpenChange(false)
  }
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      {/* The sheet is drawn at the end of the document, outside the frame: the spec's variables are given again. */}
      <SheetContent side="left" className="w-(--x-drawer) gap-0 overflow-y-auto p-0 sm:max-w-(--x-drawer)"
        style={xspecVariables()} data-testid="drawer" onClick={closeOnLink}>
        <SheetHeader className="gap-1 p-(--x-side)">
          <SheetTitle className="sr-only">{t('frame.drawer.title')}</SheetTitle>
          <SheetDescription className="sr-only">{t('frame.drawer.account')}</SheetDescription>
          <FrameLink to={DRAWER_ROWS[0].path} aria-label={t('frame.areas.me')} className="w-fit rounded-full outline-none focus-visible:ring-2 focus-visible:ring-ring">
            <Initial className="size-10 text-base" />
          </FrameLink>
          <p className="truncate pt-2 text-[17px] leading-5 font-bold" data-testid="drawer-name">{name}</p>
          {email && name !== email && <p className="text-muted-foreground truncate text-[15px] leading-5">{email}</p>}
          {prizes !== undefined && (
            <FrameLink to={PRIZES_PATH} className="text-muted-foreground w-fit pt-2 text-sm outline-none hover:underline focus-visible:ring-2 focus-visible:ring-ring"
              data-testid="drawer-counts">
              <span className="text-foreground font-bold tabular-nums">{prizes}</span> {t('me.numbers.prizes')}
            </FrameLink>
          )}
        </SheetHeader>
        <nav aria-label={t('frame.drawer.title')} className="px-2">
          <ul className="flex flex-col">
            {DRAWER_ROWS.map((row) => <DrawerRow key={row.key} row={row} />)}
            {sponsor && <DrawerRow row={{ key: 'my-sponsor', path: MY_SPONSOR_PATH, icon: Store, title: 'mySponsor.nav' }} />}
          </ul>
        </nav>
        <div className="mt-auto border-t p-(--x-side)">
          <Button variant="ghost" className="w-full justify-start" data-testid="drawer-sign-out"
            onClick={() => { onOpenChange(false); signOut() }}>
            <LogOut aria-hidden />
            {tQb('signOut')}
          </Button>
        </div>
      </SheetContent>
    </Sheet>
  )
}
