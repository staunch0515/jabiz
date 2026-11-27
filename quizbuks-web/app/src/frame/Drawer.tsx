import { useAuth } from '@jabiz/client'
import { cn, Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@jabiz/ui'
import { useQuery } from '@tanstack/react-query'
import { createContext, useContext, useMemo, useState, type MouseEvent, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { APP } from '../messages'
import { Avatar } from '../avatar/Avatar'
import { useMySponsor } from '../mysponsor/api'
import { PROFILE_QUERY } from '../pages/ProfileForm'
import { readMyProfile } from '../qb/api'
import { QB } from '../qb/messages'
import { readStaffOf, STAFF_QUERY } from '../staff/api'
import { readWallet, WALLET_SUMMARY_QUERY } from '../wallet/api'
import { DRAWER_MORE, DRAWER_ROWS, PRIZES_PATH, type MeRow } from './areas'
import { FrameLink } from './FrameLink'
import { useOpenPrizes } from './prizes'
import { useSignOut } from './signOut'
import { xspecVariables } from './xspec'
import './messages'

/*
 * The drawer of the taker app (Q113 §9.1 "侧边抽屉", §9.2; the prototype's Drawer): from the left, 280 px, opened by the
 * avatar of a head. At the top the initial (40 px, to the taker's page), the name (17 px bold), the city, and a line of
 * two numbers (Kudos, prizes to collect); the rows of `DRAWER_ROWS` (56 px, the icon 24 and the text 20 px bold, 24
 * apart); under a divider the short rows of `DRAWER_MORE` (48 px, 15 px bold) and signing out. A row followed closes it.
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

/** The taker's Kudos at hand as a bare number (the prototype's "100 学分"); undefined until read. */
export function useKudosAtHand(enabled = true): string | undefined {
  const wallet = useQuery({ queryKey: WALLET_SUMMARY_QUERY, queryFn: readWallet, enabled })
  const available = wallet.data ? Number(wallet.data.available) : undefined
  return available === undefined || Number.isNaN(available) ? undefined : available.toLocaleString()
}

const ROW = 'focus-visible:ring-ring hover:bg-accent flex shrink-0 items-center px-(--x-side) outline-none focus-visible:ring-2 focus-visible:ring-inset'

function DrawerRow({ row }: { row: MeRow }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const Icon = row.icon
  return (
    <FrameLink to={row.path} data-testid={`drawer-${row.key}`}
      className={cn(ROW, 'h-14 gap-6 text-(length:--x-drawer-text) leading-6 font-bold')}>
      <Icon aria-hidden className="size-6 shrink-0" strokeWidth={1.9} />
      <span className="truncate">{row.ns === 'qb' ? tQb(row.title) : t(row.title)}</span>
    </FrameLink>
  )
}

function Drawer({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { t } = useTranslation(APP)
  const { t: tQb } = useTranslation(QB)
  const { email } = useAuth()
  const profile = useQuery({ queryKey: PROFILE_QUERY, queryFn: readMyProfile })
  const sponsor = useMySponsor().data
  const staff = useQuery({ queryKey: STAFF_QUERY, queryFn: readStaffOf, enabled: open, retry: false })
  const prizes = useOpenPrizes(open)
  const kudos = useKudosAtHand(open)
  const signOut = useSignOut()
  const name = profile.data?.displayName || email || ''
  const shown = (only?: 'sponsor' | 'staff') => !only || (only === 'sponsor' ? !!sponsor : !!staff.data)
  // A link followed in the drawer closes it, whichever row it was.
  const closeOnLink = (event: MouseEvent<HTMLElement>) => {
    if ((event.target as HTMLElement).closest('a[href]')) onOpenChange(false)
  }
  return (
    <Sheet open={open} onOpenChange={onOpenChange}>
      {/* The sheet is drawn at the end of the document, outside the frame: the spec's variables are given again. */}
      {/* No close button (the prototype's): tapping outside or Escape closes it, as a link followed does. */}
      <SheetContent side="left" showCloseButton={false} className="w-(--x-drawer) gap-0 overflow-y-auto p-0 sm:max-w-(--x-drawer)"
        style={xspecVariables()} data-testid="drawer" onClick={closeOnLink}>
        <SheetHeader className="gap-3 px-(--x-side) pt-(--x-side) pb-3">
          <SheetTitle className="sr-only">{t('frame.drawer.title')}</SheetTitle>
          <SheetDescription className="sr-only">{t('frame.drawer.account')}</SheetDescription>
          <FrameLink to={DRAWER_ROWS[0].path} aria-label={t('frame.drawer.rows.me')}
            className="focus-visible:ring-ring w-fit rounded-full outline-none focus-visible:ring-2">
            <Avatar className="size-10 text-[17px]" />
          </FrameLink>
          <div className="flex min-w-0 flex-col">
            <p className="truncate text-[17px] leading-5 font-bold" data-testid="drawer-name">{name}</p>
            {profile.data?.city && <p className="text-muted-foreground truncate leading-5">{profile.data.city}</p>}
          </div>
          <div className="flex gap-5 text-sm leading-4" data-testid="drawer-counts">
            {kudos !== undefined && (
              <FrameLink to={DRAWER_ROWS[1].path} className="text-muted-foreground outline-none hover:underline focus-visible:underline">
                <b className="text-foreground tabular-nums">{kudos}</b> {t('me.numbers.kudos')}
              </FrameLink>
            )}
            {prizes !== undefined && (
              <FrameLink to={PRIZES_PATH} className="text-muted-foreground outline-none hover:underline focus-visible:underline">
                <b className="text-foreground tabular-nums">{prizes}</b> {t('me.numbers.prizes')}
              </FrameLink>
            )}
          </div>
        </SheetHeader>
        <nav aria-label={t('frame.drawer.title')} className="flex flex-col">
          {DRAWER_ROWS.map((row) => <DrawerRow key={row.key} row={row} />)}
          <div aria-hidden className="mx-(--x-side) my-2 border-t" />
          {DRAWER_MORE.filter((row) => shown(row.only)).map((row) => (
            <FrameLink key={row.key} to={row.path} data-testid={`drawer-${row.key}`}
              className={cn(ROW, 'h-12 text-[15px] font-bold')}>
              {row.ns === 'qb' ? tQb(row.title) : t(row.title)}
            </FrameLink>
          ))}
          <button type="button" data-testid="drawer-sign-out" className={cn(ROW, 'h-12 text-left text-[15px] font-bold')}
            onClick={() => { onOpenChange(false); signOut() }}>
            {tQb('signOut')}
          </button>
        </nav>
      </SheetContent>
    </Sheet>
  )
}
