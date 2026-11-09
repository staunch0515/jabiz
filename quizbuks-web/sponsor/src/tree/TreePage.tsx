import { PageHeader } from '@jabiz/ui'
import { useTranslation } from 'react-i18next'
import { SPONSOR } from '../messages'
import { Link } from '../qb/router'
import { PUBLICATIONS_PATH } from '../publishing/paths'
import './messages'

/**
 * The sponsor's tree (Q85). F-S2c2 (D5) removed the old tree of publications and prerequisites; until F-S3 shows the
 * knowledge maps here, the page says that it is being updated (plan F-S2 §0 item 3). Nothing is read.
 */
export function TreePage() {
  const { t } = useTranslation(SPONSOR)
  return (
    <div className="flex flex-col gap-4" data-testid="tree-page">
      <PageHeader title={t('tree.title')} />
      <p className="text-muted-foreground text-sm" data-testid="tree-soon">{t('tree.soon')}</p>
      <Link to={PUBLICATIONS_PATH} className="text-primary w-fit text-sm underline underline-offset-4">
        {t('tree.toPublications')}
      </Link>
    </div>
  )
}
