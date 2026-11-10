import { PageTabs } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { spaLink } from '../shell/links'
import { PUBLICATION_TABS, publicationTabPath, type PublicationTab } from './paths'

/**
 * The tabs of a submitted publication's page (F-F3c): links with an address each, the current one `aria-current`.
 * Shared by the page and the analytics, which keep their own address (`/publications/<id>/analytics`).
 */
export function PublicationTabs({ publicationId, current }: { publicationId: string; current: PublicationTab }) {
  const { t } = useTranslation(SPONSOR)
  return (
    <PageTabs label={t('publishing.tabs.label')} current={current}
      tabs={PUBLICATION_TABS.map((tab) => ({ key: tab, label: t(`publishing.tabs.${tab}`),
        path: publicationTabPath(publicationId, tab) }))}
      renderLink={(tab, props) => spaLink(tab.path, { ...props })} />
  )
}
