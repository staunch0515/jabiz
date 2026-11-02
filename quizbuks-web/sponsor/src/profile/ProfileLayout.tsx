import { PageHeader } from '@jabiz/ui'
import { LayoutGrid, Mail, ShoppingBag, Store, UserRound, UsersRound, type LucideIcon } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'
import { PROFILE_SECTIONS, profilePath, type ProfileSection } from './paths'

const ICONS: Record<ProfileSection, LucideIcon> = {
  overview: LayoutGrid,
  basics: UserRound,
  page: Store,
  products: ShoppingBag,
  staff: UsersRound,
  'mail-server': Mail,
}

/** The message key of a section (the hyphen is no key character). */
export const sectionKey = (section: ProfileSection) => section === 'mail-server' ? 'mailServer' : section

/**
 * The sponsor hub's frame (Q79, D-Q79-3): the title, the sub-navigation (a column from `lg` up, one scrolling row
 * below it) and the section's page. The items are links with addresses of their own; the current one carries
 * `aria-current="page"`.
 */
export function ProfileLayout({ section, children }: { section: ProfileSection; children: ReactNode }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <div className="flex flex-col gap-4" data-testid="profile" data-section={section}>
      <PageHeader title={t('profile.title')} description={t('profile.intro')} />
      <div className="flex flex-col gap-6 lg:grid lg:grid-cols-[14rem_minmax(0,1fr)] lg:items-start">
        <nav aria-label={t('profile.navLabel')} data-testid="profile-nav"
          className="-mx-4 overflow-x-auto px-4 lg:sticky lg:top-4 lg:mx-0 lg:px-0">
          <ul className="flex gap-1 lg:flex-col">
            {PROFILE_SECTIONS.map((item) => {
              const Icon = ICONS[item]
              const current = item === section
              return (
                <li key={item} className="shrink-0">
                  <Link to={profilePath(item)} current={current} data-testid={`profile-nav-${item}`}
                    className={`flex items-center gap-2 rounded-md px-3 py-2 text-sm whitespace-nowrap
                      ${current ? 'bg-accent text-accent-foreground font-medium' : 'text-muted-foreground hover:bg-accent/60 hover:text-foreground'}`}>
                    <Icon aria-hidden className="size-4 shrink-0" />
                    <span className="flex flex-col">
                      <span>{t(`profile.nav.${sectionKey(item)}`)}</span>
                      <span className="text-muted-foreground hidden text-xs font-normal lg:inline">
                        {t(`profile.navHint.${sectionKey(item)}`)}
                      </span>
                    </span>
                  </Link>
                </li>
              )
            })}
          </ul>
        </nav>
        <div className="flex min-w-0 flex-col gap-6" data-testid="profile-content">{children}</div>
      </div>
    </div>
  )
}

/**
 * A section's two columns (D-Q79-5): the form (or list) on the left, an aside with the explanation and a preview
 * on the right from `lg` up; below it the aside follows the form.
 */
export function ProfileColumns({ children, aside }: { children: ReactNode; aside?: ReactNode }) {
  return (
    <div className="flex flex-col gap-6 lg:grid lg:grid-cols-[minmax(0,1fr)_20rem] lg:items-start">
      <div className="flex min-w-0 flex-col gap-6">{children}</div>
      {aside && <aside className="flex flex-col gap-4 lg:sticky lg:top-4" data-testid="profile-aside">{aside}</aside>}
    </div>
  )
}
