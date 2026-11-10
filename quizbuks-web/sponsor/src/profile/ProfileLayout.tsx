import { PageTabs } from '@jabiz/ui'
import { UserPlus } from 'lucide-react'
import type { ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { AreaPageHeader } from '../shell/AreaPageHeader'
import { spaLink } from '../shell/links'
import { mainAction } from '../shell/mainAction'
import { STATS_PATH } from '../shell/paths'
import { profilePath, type ProfileSection } from './paths'
import { TransferMenu, TransferTools } from './TransferMenu'

/** The staff page's main button takes the sponsor to the field that names a new staff member (Q39). */
function focusStaffId() {
  const field = document.querySelector<HTMLInputElement>('[data-testid="staff-id"]')
  field?.scrollIntoView({ block: 'center' })
  field?.focus()
}

/** The message key of a section (the hyphen is no key character). */
export const sectionKey = (section: ProfileSection) => section === 'mail-server' ? 'mailServer' : section

/**
 * The sponsor hub's frame (Q79; F-F3: the area "Sponsor hub", its sections the area's tabs under the title): the
 * section's name with the hub's icon, the line about it, the hub's tabs, and the section's page.
 */
export function ProfileLayout({ section, children }: { section: ProfileSection; children: ReactNode }) {
  const { t } = useTranslation(SPONSOR)
  const frame = (
    <div className="flex flex-col gap-4" data-testid="profile" data-section={section}>
      <AreaPageHeader title={t(`profile.nav.${sectionKey(section)}`)}
        description={t(`profile.navHint.${sectionKey(section)}`)}
        primaryAction={section === 'staff' ? mainAction({ label: t('publishing.staff.addTitle'), icon: UserPlus,
          testId: 'staff-add-open', onClick: focusStaffId }) : undefined}
        actions={section === 'overview' ? <TransferMenu /> : undefined} />
      <div className="flex min-w-0 flex-col gap-6" data-testid="profile-content">{children}</div>
    </div>
  )
  // F-F3d: the overview's head has "Import and export" (the profile package, the sponsor package).
  return section === 'overview' ? <TransferTools>{frame}</TransferTools> : frame
}

/**
 * The public page's two parts (F-F3, the controller 2026-11-09): editing the page, and its statistics (the visits and
 * the products' clicks, formerly the settings' "statistics"), each with an address of its own.
 */
export function PublicPageParts({ current }: { current: 'edit' | 'stats' }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <PageTabs label={t('shell.page.label')} current={current} className="-mt-2"
      tabs={[
        { key: 'edit', label: t('shell.page.edit'), path: profilePath('page') },
        { key: 'stats', label: t('shell.page.stats'), path: STATS_PATH },
      ]}
      renderLink={(tab, props) => spaLink(tab.path, { ...props, 'data-testid': `page-part-${tab.key}` })} />
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
