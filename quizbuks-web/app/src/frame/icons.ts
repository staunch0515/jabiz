import { createLucideIcon, type LucideIcon } from 'lucide-react'
import { createElement, forwardRef } from 'react'

/*
 * The navigation's icons as the prototype draws them ("学者 App 原型 · 方案 A": BottomNav, Drawer, Desktop, Main), the
 * outlines of x.com's own: one path each on a 24 px grid, drawn by lucide's renderer so that they take the same props
 * (size, stroke) as the app's other icons. The bottom menu and the left column show the current area's icon solid
 * where the prototype does (home, notifications): `solid(icon)`.
 */

const icon = (name: string, d: string) => createLucideIcon(name, [['path', { d, key: 'p' }]])

export const HomeIcon = icon('x-home', 'M3 10.5 12 3l9 7.5V21h-6v-6H9v6H3z')
export const SearchIcon = icon('x-search', 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4')
export const HistoryIcon = icon('x-history', 'M3 12a9 9 0 1 0 3-6.7L3 8M3 3v5h5M12 7v5l3 2')
export const BellIcon = icon('x-bell', 'M6 9a6 6 0 0 1 12 0c0 6.5 3 8 3 8H3s3-1.5 3-8zM10 21h4')
export const PersonIcon = icon('x-person', 'M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM4 21a8 8 0 0 1 16 0')
export const ScanIcon = icon('x-scan', 'M4 8V5a1 1 0 0 1 1-1h3M16 4h3a1 1 0 0 1 1 1v3M20 16v3a1 1 0 0 1-1 1h-3M8 20H5a1 1 0 0 1-1-1v-3M4 12h16')
export const SettingsIcon = icon('x-settings',
  'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM12 2v3M12 19v3M4.2 4.2l2.1 2.1M17.7 17.7l2.1 2.1M2 12h3M19 12h3M4.2 19.8l2.1-2.1M17.7 6.3l2.1-2.1')
export const KudosIcon = icon('x-kudos', 'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20zM12 7v10M9 9.5h4.5a2 2 0 0 1 0 4H9')
export const GiftIcon = icon('x-gift',
  'M20 12v9H4v-9M2 7h20v5H2zM12 21V7M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7zM12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z')
export const CertificateIcon = icon('x-certificate', 'M12 15a6 6 0 1 0 0-12 6 6 0 0 0 0 12zM8.5 14 7 22l5-3 5 3-1.5-8')
export const MapIcon = icon('x-map',
  'M6 8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM18 8.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM12 20.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5zM7.5 8 11 16M16.5 8 13 16')
export const ReferIcon = icon('x-refer', 'M9 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zM2 21a7 7 0 0 1 14 0M19 8v6M16 11h6')

/** The icon filled with the current colour: the current area's, where the prototype marks it solid. */
export function solid(Icon: LucideIcon): LucideIcon {
  const Solid = forwardRef<SVGSVGElement, Parameters<LucideIcon>[0]>((props, ref) =>
    createElement(Icon, { ...props, ref, fill: 'currentColor' }))
  Solid.displayName = `${Icon.displayName ?? 'Icon'}Solid`
  return Solid as LucideIcon
}

/**
 * The floating button's scan, 24 px with the prototype's stroke of 2.2. Sized on the icon itself: the platform's
 * button gives an icon with no size class its own 16 px, over the `Fab`'s 24.
 */
export const FabScanIcon: LucideIcon = forwardRef<SVGSVGElement, Parameters<LucideIcon>[0]>((props, ref) =>
  createElement(ScanIcon, { strokeWidth: 2.2, ...props, ref, className: ['size-6', props.className].filter(Boolean).join(' ') }))
FabScanIcon.displayName = 'FabScanIcon'

/** The home's filters (the prototype's sliders: three lines, a knob on each). */
export const FilterIcon = createLucideIcon('x-filter', [
  ['path', { d: 'M4 6h9M17 6h3M4 12h3M11 12h9M4 18h11M19 18h1', key: 'lines' }],
  ['circle', { cx: '15', cy: '6', r: '2', key: 'a' }],
  ['circle', { cx: '9', cy: '12', r: '2', key: 'b' }],
  ['circle', { cx: '17', cy: '18', r: '2', key: 'c' }],
])

export const SolidHomeIcon = solid(HomeIcon)
export const SolidBellIcon = solid(BellIcon)
